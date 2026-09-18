package com.freshman.rag;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.freshman.entity.KbChunk;
import com.freshman.entity.KbDocument;
import com.freshman.mapper.KbChunkMapper;
import com.freshman.mapper.KbDocumentMapper;
import com.freshman.rag.dto.ScoredChunk;
import com.freshman.rag.tokenizer.ChineseTokenizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 关键词检索路径（TF-IDF 余弦）
 *
 * 三个关键设计：
 * 1. **自建语料，不依赖 {@link VectorIndex}** —— 否则向量索引为空时
 *    "降级为纯关键词模式"就无从谈起（G8 的前提）。
 * 2. **语料范围是 `status IN (1,2)` 且 content 非空**，不是 `status=1`：
 *    关键词匹配不依赖向量。若沿用 `status=1`，向量化失败的 chunk
 *    （status=2，但正文明明可用）会对**所有**检索路径永久不可见。
 * 3. **打分只用纯 TF-IDF 余弦**，**不含**分类底分与 priority 底分 ——
 *    那正是缺陷 1（"任何问题都能凑满 Top3"）的成因。
 *    与改造前逐例一致的"本地匹配"由 LocalKnowledgeEngine 负责（降级路径）。
 *
 * 所属模块：AI 智能问答模块 / RAG
 * @author AI Module Team
 * @version 1.0
 */
@Component
public class KeywordRetriever {

    private static final Logger log = LoggerFactory.getLogger(KeywordRetriever.class);

    private final KbChunkMapper chunkMapper;
    private final KbDocumentMapper documentMapper;
    private final ChineseTokenizer tokenizer;

    /** 当前语料快照；rebuild() 完成后原子替换 */
    private volatile Snapshot snapshot = Snapshot.EMPTY;

    public KeywordRetriever(KbChunkMapper chunkMapper, KbDocumentMapper documentMapper,
                            ChineseTokenizer tokenizer) {
        this.chunkMapper = chunkMapper;
        this.documentMapper = documentMapper;
        this.tokenizer = tokenizer;
    }

    public int size() {
        return snapshot.metas.length;
    }

    public boolean isEmpty() {
        return snapshot.metas.length == 0;
    }

    /**
     * 重建关键词语料与 IDF 统计。
     * 语料 = `status IN (1,2)` 且 content 非空的 chunk（含向量化失败的块）。
     */
    public synchronized void rebuild() {
        List<KbChunk> rows = chunkMapper.selectList(new LambdaQueryWrapper<KbChunk>()
                .in(KbChunk::getStatus, 1, 2)
                .isNotNull(KbChunk::getContent));

        List<ChunkMeta> metas = new ArrayList<>();
        for (KbChunk row : rows) {
            if (row.getContent() == null || row.getContent().isBlank()) {
                continue;
            }
            if (row.getStatus() == null || (row.getStatus() != 1 && row.getStatus() != 2)) {
                continue;
            }
            metas.add(new ChunkMeta(row));
        }
        fillDocumentMeta(metas);

        // 逐块取 token（content + search_terms + 文档标题）
        List<Set<String>> docTokens = new ArrayList<>(metas.size());
        Map<String, Integer> df = new HashMap<>();
        for (ChunkMeta m : metas) {
            Set<String> tokens = new LinkedHashSet<>(
                    tokenizer.expandSynonyms(tokenizer.tokenize(m.textForKeywords())));
            tokens.removeIf(t -> t == null || t.isEmpty());
            docTokens.add(tokens);
            for (String t : tokens) {
                df.merge(t, 1, Integer::sum);
            }
        }

        int n = Math.max(1, metas.size());
        Map<String, Double> idf = new HashMap<>(df.size());
        for (Map.Entry<String, Integer> e : df.entrySet()) {
            // 平滑 IDF，恒为正（保证余弦落在 [0,1]）
            idf.put(e.getKey(), Math.log((n + 1.0) / (e.getValue() + 1.0)) + 1.0);
        }

        // 逐块 TF-IDF 向量 + L2 归一化（归一化后点积即余弦）
        Map<String, Double>[] vectors = new Map[metas.size()];
        for (int i = 0; i < metas.size(); i++) {
            vectors[i] = tfidf(docTokens.get(i), idf);
        }

        snapshot = new Snapshot(metas.toArray(new ChunkMeta[0]), vectors, idf);
        log.info("[RAG] 关键词语料已重建：{} 条（含 status=2 的块），词表 {} 项",
                snapshot.metas.length, idf.size());
    }

    /**
     * 关键词召回：返回按原始 TF-IDF 余弦降序的 Top-K。
     * 返回的 ScoredChunk 只填 keywordScore，vectorCosine 为 null。
     */
    public List<ScoredChunk> search(String question, int topK) {
        Snapshot snap = this.snapshot;
        if (question == null || question.isBlank() || snap.metas.length == 0 || topK <= 0) {
            return List.of();
        }
        Set<String> qTokens = new LinkedHashSet<>(
                tokenizer.expandSynonyms(tokenizer.tokenize(question)));
        Map<String, Double> qVec = tfidf(qTokens, snap.idf);
        if (qVec.isEmpty()) {
            return List.of();
        }

        double[] scores = new double[snap.metas.length];
        for (int i = 0; i < snap.metas.length; i++) {
            scores[i] = cosine(qVec, snap.vectors[i]);
        }

        int k = Math.min(topK, snap.metas.length);
        PriorityQueue<Integer> heap = new PriorityQueue<>(Comparator.comparingDouble(i -> scores[i]));
        for (int i = 0; i < snap.metas.length; i++) {
            if (scores[i] <= 0) {
                continue;
            }
            if (heap.size() < k) {
                heap.offer(i);
            } else if (scores[i] > scores[heap.peek()]) {
                heap.poll();
                heap.offer(i);
            }
        }

        List<Integer> picked = new ArrayList<>(heap);
        picked.sort((a, b) -> Double.compare(scores[b], scores[a]));
        List<ScoredChunk> out = new ArrayList<>(picked.size());
        for (Integer i : picked) {
            ChunkMeta m = snap.metas[i];
            ScoredChunk sc = new ScoredChunk();
            sc.setChunkId(m.chunkId);
            sc.setDocumentId(m.documentId);
            sc.setChunkIndex(m.chunkIndex == null ? 0 : m.chunkIndex);
            sc.setContent(m.content);
            sc.setSnippet(snippet(m.content));
            sc.setTitle(m.title);
            sc.setUrlPath(m.urlPath);
            sc.setSourceType(m.sourceType);
            sc.setSourceId(m.sourceId);
            sc.setKeywordScore(scores[i]);        // 只填关键词分；vectorCosine 保持 null
            out.add(sc);
        }
        return out;
    }

    // ==================== 内部实现 ====================

    /** 词集合 → 归一化 TF-IDF 向量 */
    private static Map<String, Double> tfidf(Set<String> tokens, Map<String, Double> idf) {
        Map<String, Double> vec = new HashMap<>();
        if (tokens == null || tokens.isEmpty()) {
            return vec;
        }
        double len = tokens.size();
        for (String t : tokens) {
            Double w = idf.get(t);
            if (w == null) {
                continue;   // 查询词不在语料词表中 → 无区分力
            }
            vec.merge(t, (1.0 / len) * w, Double::sum);
        }
        // L2 归一化
        double sum = 0;
        for (double v : vec.values()) {
            sum += v * v;
        }
        double norm = Math.sqrt(sum);
        if (norm > 0) {
            vec.replaceAll((k, v) -> v / norm);
        }
        return vec;
    }

    /** 两个已归一化向量的点积 = 余弦；权重恒为正 → 结果落在 [0,1] */
    private static double cosine(Map<String, Double> a, Map<String, Double> b) {
        if (a.isEmpty() || b.isEmpty()) {
            return 0;
        }
        Map<String, Double> small = a.size() <= b.size() ? a : b;
        Map<String, Double> large = small == a ? b : a;
        double s = 0;
        for (Map.Entry<String, Double> e : small.entrySet()) {
            Double other = large.get(e.getKey());
            if (other != null) {
                s += e.getValue() * other;
            }
        }
        return s;
    }

    private static String snippet(String content) {
        if (content == null) {
            return null;
        }
        return content.length() <= 200 ? content : content.substring(0, 200);
    }

    /** 批量补文档元信息；查不到的文档保留空标题（不丢 chunk） */
    private void fillDocumentMeta(List<ChunkMeta> metas) {
        Set<Long> ids = new HashSet<>();
        for (ChunkMeta m : metas) {
            if (m.documentId != null) {
                ids.add(m.documentId);
            }
        }
        if (ids.isEmpty()) {
            return;
        }
        Map<Long, KbDocument> docs = new HashMap<>();
        for (KbDocument d : documentMapper.selectBatchIds(ids)) {
            docs.put(d.getId(), d);
        }
        for (ChunkMeta m : metas) {
            KbDocument d = docs.get(m.documentId);
            if (d != null) {
                m.title = d.getTitle();
                m.urlPath = d.getUrlPath();
                m.sourceType = d.getSourceType();
                m.sourceId = d.getSourceId();
            }
        }
    }

    /** 关键词语料的元数据 + 参与匹配的文本 */
    static final class ChunkMeta {
        final Long chunkId;
        final Long documentId;
        final Integer chunkIndex;
        final String content;
        final String searchTerms;
        String title;
        String urlPath;
        String sourceType;
        Long sourceId;

        ChunkMeta(KbChunk row) {
            this.chunkId = row.getId();
            this.documentId = row.getDocumentId();
            this.chunkIndex = row.getChunkIndex();
            this.content = row.getContent();
            this.searchTerms = row.getSearchTerms();
        }

        /**
         * 参与关键词匹配的文本 = content + search_terms + 文档标题。
         * 注意：search_terms 只在这里参与匹配，**绝不写回 content**
         * （避免污染向量语义 —— 见规格 §4.3）。
         */
        String textForKeywords() {
            StringBuilder sb = new StringBuilder();
            if (content != null) {
                sb.append(content);
            }
            if (searchTerms != null && !searchTerms.isBlank()) {
                sb.append('\n').append(searchTerms);
            }
            if (title != null && !title.isBlank()) {
                sb.append('\n').append(title);
            }
            return sb.toString();
        }
    }

    /** 不可变快照；volatile 原子替换 */
    static final class Snapshot {
        static final Snapshot EMPTY = new Snapshot(new ChunkMeta[0], new Map[0], Map.of());

        final ChunkMeta[] metas;
        final Map<String, Double>[] vectors;
        final Map<String, Double> idf;

        Snapshot(ChunkMeta[] metas, Map<String, Double>[] vectors, Map<String, Double> idf) {
            this.metas = metas;
            this.vectors = vectors;
            this.idf = idf;
        }
    }
}
