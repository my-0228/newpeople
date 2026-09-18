package com.freshman.rag;

import cn.hutool.json.JSONUtil;
import com.freshman.entity.KbChunk;
import com.freshman.entity.KbDocument;
import com.freshman.rag.dto.Chunk;
import com.freshman.rag.dto.IndexReport;
import com.freshman.rag.source.DocumentSource;
import com.freshman.rag.store.KbStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/**
 * 知识库索引器：抽取 → 清洗 → 切分 → 向量化 → 落库 → 重建内存索引
 *
 * 增量重建的关键顺序（**必须严格遵守**）：
 *   ① 先读旧 chunk 的 (content_hash → embedding) 映射
 *   ② 再删除旧 chunk
 *   ③ 对内容未变的 chunk 复用旧向量（不调 Embedding API）
 * 若颠倒 ① 与 ②，旧向量已被删除，复用无从谈起 —— 会导致每次重建都全量重嵌。
 *
 * 隐私约束：club 的 president/contact、activity 的 contact、guide_teacher 整表
 * 都不进入知识库（在各自的 DocumentSource 里已排除）。
 *
 * 所属模块：AI 智能问答模块 / RAG
 * @author AI Module Team
 * @version 1.0
 */
@Service
public class KnowledgeIndexer {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeIndexer.class);

    /** 与库表长度一致的上限，防止超长插入失败（严格模式）或静默截断 */
    private static final int MAX_TITLE = 300;
    private static final int MAX_URL_PATH = 200;
    private static final int MAX_SEARCH_TERMS = 1000;

    private final List<DocumentSource> sources;
    private final KbStore store;
    private final ChunkSplitter splitter;
    private final EmbeddingClient embedder;
    private final VectorIndex vectorIndex;
    private final KeywordRetriever keywordRetriever;
    private final RagProperties props;

    public KnowledgeIndexer(List<DocumentSource> sources,
                            KbStore store,
                            ChunkSplitter splitter,
                            EmbeddingClient embedder,
                            VectorIndex vectorIndex,
                            KeywordRetriever keywordRetriever,
                            RagProperties props) {
        // 按 sourceType 排序，保证重建顺序稳定、报告可复现
        List<DocumentSource> sorted = new ArrayList<>(sources);
        sorted.sort(Comparator.comparing(DocumentSource::sourceType));
        this.sources = sorted;
        this.store = store;
        this.splitter = splitter;
        this.embedder = embedder;
        this.vectorIndex = vectorIndex;
        this.keywordRetriever = keywordRetriever;
        this.props = props;
    }

    /**
     * 全量/增量重建。
     *
     * @param force true 时忽略 content_hash，强制重新切分与向量化
     */
    public IndexReport rebuildAll(boolean force) {
        long start = System.currentTimeMillis();
        int[] c = new int[5];   // documentCount, chunkCount, embeddedCount, skippedCount, failedCount

        for (DocumentSource source : sources) {
            List<DocumentSource.RawDocument> docs;
            try {
                docs = source.extract();
            } catch (Exception e) {
                log.warn("[RAG] 来源 {} 抽取失败，跳过：{}", source.sourceType(), e.getMessage());
                continue;
            }
            for (DocumentSource.RawDocument doc : docs) {
                try {
                    indexOne(source, doc, force, c);
                } catch (Exception e) {
                    // 单个文档失败不影响其它文档，也不中断整体重建
                    log.warn("[RAG] 文档 {}/{} 索引失败：{}", source.sourceType(), doc.sourceId(), e.getMessage());
                }
            }
        }

        // 索引重建（两个索引各自吞异常，任一失败不影响数据落库结果）
        try {
            vectorIndex.rebuild();
        } catch (Exception e) {
            log.warn("[RAG] 索引重建后刷新向量索引失败：{}", e.getMessage());
        }
        try {
            keywordRetriever.rebuild();
        } catch (Exception e) {
            log.warn("[RAG] 索引重建后刷新关键词语料失败：{}", e.getMessage());
        }

        IndexReport report = new IndexReport(c[0], c[1], c[2], c[3], c[4],
                System.currentTimeMillis() - start);
        log.info("[RAG] 索引完成：{}", report);
        return report;
    }

    // ==================== 单个文档 ====================

    private void indexOne(DocumentSource source, DocumentSource.RawDocument doc, boolean force, int[] c) {
        Long sourceId = doc.sourceId();
        if (sourceId == null) {
            // 契约：sourceId 永不为 null（唯一键不阻止 NULL 重复行，"一来源一文档"靠代码保证）
            log.warn("[RAG] 来源 {} 出现 sourceId 为 null 的行，已跳过", source.sourceType());
            return;
        }
        String body = doc.body() == null ? "" : doc.body();
        if (body.isBlank()) {
            return;
        }
        c[0]++;   // documentCount

        String docHash = sha256(body);
        Optional<KbDocument> existing = store.findDocument(source.sourceType(), sourceId);

        // 文档内容未变 → 整篇跳过（计数按 chunk 计，见 IndexReport 语义）
        // 但若该文档下存在向量化失败的 chunk（status=2），则不跳过：重新走一遍流程，
        // 内容未变的块会复用旧向量（不再花钱），只有失败块会被重试 → 失败可自愈。
        if (existing.isPresent() && !force && docHash.equals(existing.get().getContentHash())) {
            boolean hasFailedChunk = false;
            for (KbChunk old : store.findChunks(existing.get().getId())) {
                if (old.getStatus() != null && old.getStatus() == 2) {
                    hasFailedChunk = true;
                    break;
                }
            }
            if (!hasFailedChunk) {
                c[3] += nz(existing.get().getChunkCount());
                return;
            }
            log.info("[RAG] 文档 {}/{} 存在向量化失败的块，本次重试", source.sourceType(), sourceId);
        }

        // ① 【先】读旧向量：必须在删除之前
        Map<String, String> oldEmbeddings = new HashMap<>();
        if (existing.isPresent() && existing.get().getId() != null) {
            for (KbChunk old : store.findChunks(existing.get().getId())) {
                if (old.getContentHash() != null && old.getEmbedding() != null) {
                    oldEmbeddings.put(old.getContentHash(), old.getEmbedding());
                }
            }
        }

        // 写文档（显式设置状态，不依赖 DB 默认值）
        KbDocument target = existing.orElseGet(KbDocument::new);
        target.setSourceType(source.sourceType());
        target.setSourceId(sourceId);
        target.setTitle(truncate(doc.title(), MAX_TITLE));
        target.setCategory(truncate(doc.category(), 50));
        target.setUrlPath(truncate(source.urlPathFor(doc), MAX_URL_PATH));
        target.setContentHash(docHash);
        target.setStatus(1);
        Long docId = store.saveDocument(target);
        if (docId == null) {
            log.warn("[RAG] 文档 {}/{} 保存后未拿到 id，跳过", source.sourceType(), sourceId);
            return;
        }

        // ② 【后】删除旧 chunk
        store.deleteChunks(docId);

        // 切分
        List<Chunk> chunks = source.needsSplitting()
                ? splitter.split(body, new ChunkSplitter.Options(
                        props.getChunk().getSize(), props.getChunk().getOverlap(), props.getChunk().getMinSize()))
                : List.of(new Chunk(0, body, 0, body.length()));
        if (chunks.isEmpty()) {
            store.updateChunkCount(docId, 0);
            return;
        }

        // 决定复用还是重新向量化
        String[] hashes = new String[chunks.size()];
        String[] reused = new String[chunks.size()];
        List<String> toEmbed = new ArrayList<>();
        List<Integer> toEmbedIdx = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            String h = sha256(chunks.get(i).getContent());
            hashes[i] = h;
            String oldVec = force ? null : oldEmbeddings.get(h);
            if (oldVec != null) {
                reused[i] = oldVec;
            } else {
                toEmbed.add(chunks.get(i).getContent());
                toEmbedIdx.add(i);
            }
        }

        List<float[]> vectors = toEmbed.isEmpty() ? List.of() : embedder.embedBatch(toEmbed);

        // 落库（逐条插入；单条失败只影响自己）
        for (int i = 0; i < chunks.size(); i++) {
            Chunk ch = chunks.get(i);
            String embeddingJson = reused[i];
            if (embeddingJson == null) {
                int pos = toEmbedIdx.indexOf(i);
                float[] v = (pos >= 0 && pos < vectors.size()) ? vectors.get(pos) : null;
                if (v == null) {
                    c[4]++;                       // failedCount
                } else {
                    embeddingJson = JSONUtil.toJsonStr(v);
                    c[2]++;                       // embeddedCount
                }
            }
            KbChunk kc = new KbChunk();
            kc.setDocumentId(docId);
            kc.setChunkIndex(ch.getIndex());
            kc.setContent(ch.getContent());
            kc.setSearchTerms(truncateSearchTerms(doc.searchTerms()));
            kc.setCharStart(ch.getCharStart());
            kc.setCharEnd(ch.getCharEnd());
            kc.setContentHash(hashes[i]);
            kc.setEmbedding(embeddingJson);
            kc.setEmbeddingModel(embeddingJson == null ? null : props.getEmbedding().getModel());
            kc.setDim(embeddingJson == null ? null : props.getEmbedding().getDimensions());
            kc.setStatus(embeddingJson == null ? 2 : 1);
            try {
                store.insertChunk(kc);
                c[1]++;                           // chunkCount
            } catch (Exception e) {
                log.warn("[RAG] chunk 写入失败（来源={}, sourceId={}, index={}）：{}",
                        source.sourceType(), sourceId, ch.getIndex(), e.getMessage());
            }
        }
        store.updateChunkCount(docId, chunks.size());
    }

    // ==================== 工具方法 ====================

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    /**
     * search_terms 按**逗号边界**截断到 1000 字符。
     * 原因：ai_knowledge.keywords/synonyms 是无界 TEXT，拼接后可能超长；
     * 严格模式下会插入失败、非严格模式下会静默截断并悄悄破坏关键词召回。
     */
    static String truncateSearchTerms(String s) {
        if (s == null) {
            return null;
        }
        if (s.length() <= MAX_SEARCH_TERMS) {
            return s;
        }
        String cut = s.substring(0, MAX_SEARCH_TERMS);
        int lastComma = cut.lastIndexOf(',');
        String result = lastComma > 0 ? cut.substring(0, lastComma) : cut;
        log.warn("[RAG] search_terms 超长（{} 字符），已按逗号边界截断到 {} 字符",
                s.length(), result.length());
        return result;
    }

    /** 正文 SHA-256（十六进制小写），用于增量重建判定 */
    static String sha256(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
