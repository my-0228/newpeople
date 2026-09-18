package com.freshman.rag;

import com.freshman.rag.dto.RetrievalResult;
import com.freshman.rag.dto.ScoredChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 混合检索器：向量召回 + 关键词召回 → RRF 融合 → **双门限** → 同文档去重
 *
 * ⚠️ 本类最关键、最容易被实现错的语义（规格 §5.5.1/§5.5.2）：
 *
 *   三种分数的语义严格区分、不可混用：
 *     vectorCosine   向量路径**原始余弦**   —— 参与门限（gateMode=vector 时）
 *     keywordScore   关键词路径**原始余弦** —— 参与门限（gateMode=keyword 时）
 *     rrfScore       融合分（值域约 0.016~0.033）—— **仅用于排序，绝不参与门限**
 *
 *   把 rrfScore 与 min-score(0.35) 这类绝对门限比较，会导致**永远拒答**、
 *   relative-floor 静默失效 —— 整个"修掉缺陷 1"的改造会静默失败。
 *   因此 HybridRetrieverTest 里有一条专门断言此事的测试。
 *
 * 门限规则（每次请求只有一种 gateMode）：
 *   向量索引非空且问题 embedding 成功 → gateMode=vector，用 vectorCosine 判定；
 *   否则                              → gateMode=keyword，用 keywordScore 判定。
 *   门限只作用于当前 gateMode 对应的那一种原始分；另一条路径的候选只参与排序。
 *   空值候选（在 vector 模式下没有 vectorCosine 的）**不进材料集**，但保留在日志/排序中。
 *
 * 所属模块：AI 智能问答模块 / RAG
 * @author AI Module Team
 * @version 1.0
 */
@Component
public class HybridRetriever {

    private static final Logger log = LoggerFactory.getLogger(HybridRetriever.class);

    public static final String GATE_VECTOR = "vector";
    public static final String GATE_KEYWORD = "keyword";

    private final VectorIndex vectorIndex;
    private final KeywordRetriever keywordRetriever;
    private final EmbeddingClient embedder;
    private final RagProperties props;

    public HybridRetriever(VectorIndex vectorIndex,
                           KeywordRetriever keywordRetriever,
                           EmbeddingClient embedder,
                           RagProperties props) {
        this.vectorIndex = vectorIndex;
        this.keywordRetriever = keywordRetriever;
        this.embedder = embedder;
        this.props = props;
    }

    /**
     * 检索并做门限判定。
     *
     * @param question 用户问题
     * @param topK     材料集上限（**尊重传入值**，仅钳制到 [1, top-k-vector]；
     *                 `top-k-final` 只约束 RAG 生成路径注入 prompt 的条数）
     */
    public RetrievalResult retrieve(String question, int topK) {
        int limit = Math.max(1, Math.min(topK, props.getTopKVector()));

        // ---------- ① 向量路径（索引非空 + 问题 embedding 成功才可用）----------
        List<ScoredChunk> vectorHits = List.of();
        boolean vectorAvailable = false;
        long embeddingMs = 0;
        if (!vectorIndex.isEmpty() && question != null && !question.isBlank()) {
            float[] qv = null;
            long t0 = System.currentTimeMillis();
            try {
                qv = embedder.embed(question);
            } catch (Exception e) {
                log.warn("[RAG] 问题向量化失败，本次转为关键词门限模式：{}", e.getMessage());
            } finally {
                embeddingMs = System.currentTimeMillis() - t0;
            }
            if (qv != null) {
                vectorHits = vectorIndex.search(qv, props.getTopKVector());
                vectorAvailable = true;
            }
        }

        // ---------- ② 关键词路径（永远可用，索引为空也能走 keyword 门限）----------
        List<ScoredChunk> keywordHits = keywordRetriever.search(question, props.getTopKKeyword());

        // ---------- ③ 按 chunkId 合并并计算 RRF（仅用于排序）----------
        Map<Long, ScoredChunk> merged = merge(vectorHits, keywordHits);

        // ---------- ④ 门限判定 ----------
        String gateMode = vectorAvailable ? GATE_VECTOR : GATE_KEYWORD;
        double topScore;
        List<ScoredChunk> qualified = new ArrayList<>();

        if (GATE_VECTOR.equals(gateMode)) {
            Double vectorTop1 = topRawScore(merged.values(), true);
            topScore = vectorTop1 == null ? 0.0 : vectorTop1;
            if (vectorTop1 != null && vectorTop1 >= props.getMinScore()) {
                double floor = props.getRelativeFloor() * vectorTop1;
                for (ScoredChunk c : merged.values()) {
                    // 空值规则：gateMode=vector 时，没有 vectorCosine 的候选不进材料集
                    if (c.getVectorCosine() != null && c.getVectorCosine() >= floor) {
                        qualified.add(c);
                    }
                }
            }
        } else {
            Double keywordTop1 = topRawScore(merged.values(), false);
            topScore = keywordTop1 == null ? 0.0 : keywordTop1;
            if (keywordTop1 != null && keywordTop1 >= props.getKeywordMinScore()) {
                double floor = props.getRelativeFloor() * keywordTop1;
                for (ScoredChunk c : merged.values()) {
                    if (c.getKeywordScore() != null && c.getKeywordScore() >= floor) {
                        qualified.add(c);
                    }
                }
            }
        }

        // ---------- ⑤ 排序 + 同文档去重 + 截断 ----------
        qualified.sort(Comparator.comparingDouble(ScoredChunk::getRrfScore).reversed());
        List<ScoredChunk> material = dedupByDocument(qualified, limit);

        RetrievalResult result = new RetrievalResult();
        result.setChunks(material);
        result.setHasQualifiedMaterial(!material.isEmpty());
        result.setGateMode(gateMode);
        result.setTopScore(topScore);
        result.setVectorHits(vectorHits.size());
        result.setKeywordHits(keywordHits.size());
        result.setEmbeddingMs(embeddingMs);

        log.debug("[RAG] 检索完成 gateMode={}, vectorHits={}, keywordHits={}, topScore={}, material={}",
                gateMode, vectorHits.size(), keywordHits.size(), topScore, material.size());
        return result;
    }

    /** 按 chunkId 合并两条路径，并计算 RRF 分（1/(k+rank) 求和） */
    private Map<Long, ScoredChunk> merge(List<ScoredChunk> vectorHits, List<ScoredChunk> keywordHits) {
        Map<Long, ScoredChunk> merged = new LinkedHashMap<>();
        double k = props.getRrfK();

        for (int i = 0; i < vectorHits.size(); i++) {
            ScoredChunk src = vectorHits.get(i);
            if (src.getChunkId() == null) {
                continue;
            }
            ScoredChunk target = merged.computeIfAbsent(src.getChunkId(), id -> copyMeta(src));
            target.setVectorCosine(src.getVectorCosine());
            target.setRrfScore(target.getRrfScore() + 1.0 / (k + i + 1));
        }
        for (int i = 0; i < keywordHits.size(); i++) {
            ScoredChunk src = keywordHits.get(i);
            if (src.getChunkId() == null) {
                continue;
            }
            ScoredChunk target = merged.computeIfAbsent(src.getChunkId(), id -> copyMeta(src));
            target.setKeywordScore(src.getKeywordScore());
            target.setRrfScore(target.getRrfScore() + 1.0 / (k + i + 1));
        }
        return merged;
    }

    /** 复制引用所需的元信息（两个路径都带同一份字段） */
    private static ScoredChunk copyMeta(ScoredChunk src) {
        ScoredChunk c = new ScoredChunk();
        c.setChunkId(src.getChunkId());
        c.setDocumentId(src.getDocumentId());
        c.setSourceType(src.getSourceType());
        c.setSourceId(src.getSourceId());
        c.setChunkIndex(src.getChunkIndex());
        c.setTitle(src.getTitle());
        c.setUrlPath(src.getUrlPath());
        c.setContent(src.getContent());
        c.setSnippet(src.getSnippet());
        return c;
    }

    /** 取当前模式下 top1 的原始分；无可用候选返回 null */
    private static Double topRawScore(Collection<ScoredChunk> chunks, boolean vectorMode) {
        Double best = null;
        for (ScoredChunk c : chunks) {
            Double v = vectorMode ? c.getVectorCosine() : c.getKeywordScore();
            if (v != null && (best == null || v > best)) {
                best = v;
            }
        }
        return best;
    }

    /** 同一文档最多保留 max-per-document 条（按传入顺序，即 rrfScore 降序） */
    private List<ScoredChunk> dedupByDocument(List<ScoredChunk> sorted, int limit) {
        int perDoc = Math.max(1, props.getMaxPerDocument());
        Map<Long, Integer> count = new HashMap<>();
        List<ScoredChunk> out = new ArrayList<>(limit);
        for (ScoredChunk c : sorted) {
            if (out.size() >= limit) {
                break;
            }
            Long docId = c.getDocumentId();
            if (docId != null) {
                int n = count.getOrDefault(docId, 0);
                if (n >= perDoc) {
                    continue;
                }
                count.put(docId, n + 1);
            }
            out.add(c);
        }
        return out;
    }
}
