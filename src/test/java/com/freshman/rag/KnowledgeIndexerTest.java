package com.freshman.rag;

import com.freshman.entity.KbChunk;
import com.freshman.entity.KbDocument;
import com.freshman.rag.dto.IndexReport;
import com.freshman.rag.source.AbstractDocumentSource;
import com.freshman.rag.source.DocumentSource;
import com.freshman.rag.store.KbStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * KnowledgeIndexer 单元测试。
 *
 * 用**内存版 KbStore**（而不是 mock 出一堆空返回值）来真实验证有状态行为：
 * 增量跳过、旧向量复用（这是"先读旧向量再删除"顺序的回归测试）、失败隔离。
 * 纯单元测试：不启动 Spring 上下文、不连数据库、不调用真实 Embedding API。
 */
class KnowledgeIndexerTest {

    private RagProperties props;
    private FakeKbStore store;
    private CountingEmbedder embedder;
    private VectorIndex vectorIndex;

    @BeforeEach
    void setUp() {
        props = new RagProperties();
        props.getChunk().setSize(10);
        props.getChunk().setOverlap(0);
        props.getChunk().setMinSize(3);
        props.getEmbedding().setDimensions(4);
        props.getEmbedding().setBatchSize(25);
        props.getEmbedding().setModel("text-embedding-v3");

        store = new FakeKbStore();
        embedder = new CountingEmbedder(props);
        vectorIndex = mock(VectorIndex.class);
    }

    private KnowledgeIndexer indexer(DocumentSource... sources) {
        return new KnowledgeIndexer(List.of(sources), store, new ChunkSplitter(), embedder,
                vectorIndex, mock(KeywordRetriever.class), props);
    }

    // ==================== 1. 增量跳过 + 旧向量复用 ====================

    @Test
    void unchangedContentIsSkippedOnSecondRunAndOldVectorsAreReused() {
        FakeSource src = new FakeSource("ai_knowledge", true,
                List.of(new DocumentSource.RawDocument(1L, "标题", "句子一。句子二。句子三。句子四。", "关键词,同义词", "报到流程")));
        KnowledgeIndexer indexer = indexer(src);

        IndexReport first = indexer.rebuildAll(false);
        assertTrue(first.getChunkCount() > 1, "该正文应被切成多块，实际：" + first.getChunkCount());
        assertEquals(first.getChunkCount(), first.getEmbeddedCount(), "首次应全部向量化");
        assertEquals(0, first.getSkippedCount());
        int callsAfterFirst = embedder.batchCalls.get();

        // 第二次：文档 hash 未变 → 整篇跳过，且**不调用 Embedding API**
        IndexReport second = indexer.rebuildAll(false);
        assertEquals(first.getChunkCount(), second.getSkippedCount(), "skippedCount 应按 chunk 计");
        assertEquals(0, second.getChunkCount(), "第二次不应重新落库");
        assertEquals(0, second.getEmbeddedCount(), "第二次不应重新向量化");
        assertEquals(callsAfterFirst, embedder.batchCalls.get(),
                "跳过路径绝不能调用 Embedding API（这也是'先读旧向量再删除'顺序的回归测试）");

        // 库里的 chunk 仍在，向量非空
        List<KbChunk> chunks = store.findChunks(1L);
        assertEquals(first.getChunkCount(), chunks.size());
        assertTrue(chunks.stream().allMatch(c -> c.getEmbedding() != null && c.getStatus() == 1));
    }

    @Test
    void forceRebuildReEmbedsEverything() {
        FakeSource src = new FakeSource("ai_knowledge", false,
                List.of(new DocumentSource.RawDocument(1L, "标题", "句子一。句子二。句子三。句子四。", null, null)));
        KnowledgeIndexer indexer = indexer(src);
        IndexReport first = indexer.rebuildAll(false);

        IndexReport forced = indexer.rebuildAll(true);

        assertEquals(0, forced.getSkippedCount(), "force 时不应跳过任何文档");
        assertEquals(first.getChunkCount(), forced.getChunkCount());
        assertEquals(forced.getChunkCount(), forced.getEmbeddedCount(), "force 时应全部重新向量化");
        assertEquals(2, embedder.batchCalls.get(), "两次重建应各调用一次批量向量化");
    }

    // ==================== 2. 失败隔离 ====================

    @Test
    void failedChunkIsRetriedOnNextRunWhileGoodChunksAreReused() {
        // size=10/overlap=0 → "句子一。句子二。句子三。" 与 "句子四。" 两块
        String body = "句子一。句子二。句子三。句子四。";
        embedder.failIfContains.add("句子四");   // 只让第二块失败（用包含匹配，避免依赖精确分块边界）
        FakeSource src = new FakeSource("ai_knowledge", true,
                List.of(new DocumentSource.RawDocument(1L, "标题", body, null, null)));
        KnowledgeIndexer indexer = indexer(src);

        IndexReport first = assertDoesNotThrow(() -> indexer.rebuildAll(false));
        assertEquals(2, first.getChunkCount(), "失败的 chunk 仍应落库（status=2 待重试）");
        assertEquals(1, first.getEmbeddedCount());
        assertEquals(1, first.getFailedCount());
        List<KbChunk> chunks = store.findChunks(store.docIdOf("ai_knowledge", 1L));
        assertEquals(2, chunks.size());
        assertEquals(1, chunks.stream().filter(c -> c.getStatus() == 2).count(), "应恰好一个失败块");
        assertTrue(chunks.stream().anyMatch(c -> c.getStatus() == 2 && c.getEmbedding() == null));

        // 下一次重建：文档 hash 未变，但存在失败块 → 不整篇跳过；
        // 好块复用旧向量（不再花钱），只重试失败块 → 失败可自愈
        embedder.failIfContains.clear();
        int callsBefore = embedder.batchCalls.get();
        IndexReport retry = indexer.rebuildAll(false);

        assertEquals(0, retry.getSkippedCount(), "有失败块时不应整篇跳过");
        assertEquals(2, retry.getChunkCount());
        assertEquals(1, retry.getEmbeddedCount(), "只应重试那一个失败块，好块复用旧向量");
        assertEquals(0, retry.getFailedCount());
        assertEquals(callsBefore + 1, embedder.batchCalls.get());
        assertTrue(store.findChunks(store.docIdOf("ai_knowledge", 1L)).stream()
                        .allMatch(c -> c.getStatus() == 1 && c.getEmbedding() != null),
                "重试后两块都应是 status=1 且有向量");
    }

    // ==================== 3. 报告字段 ====================

    @Test
    void documentAndChunkCountsAreReported() {
        FakeSource qa = new FakeSource("ai_knowledge", false, List.of(
                new DocumentSource.RawDocument(1L, "问题一", "问题一\n答案一", "k1,k2", "报到流程"),
                new DocumentSource.RawDocument(2L, "问题二", "问题二\n答案二", null, "报到流程")));
        FakeSource longText = new FakeSource("life_dormitory", true, List.of(
                new DocumentSource.RawDocument(9L, "厚德学区", "句子一。句子二。句子三。句子四。", null, "宿舍")),
                "/life/dormitory");
        KnowledgeIndexer indexer = indexer(qa, longText);

        IndexReport r = indexer.rebuildAll(false);

        assertEquals(3, r.getDocumentCount(), "两个 Q/A + 一个长文本");
        assertTrue(r.getChunkCount() >= 3, "Q/A 各 1 块 + 长文本多块，实际 " + r.getChunkCount());
        assertEquals(r.getChunkCount(), r.getEmbeddedCount(), "全部为新内容，应全部向量化");
        assertEquals(0, r.getSkippedCount());
        assertEquals(0, r.getFailedCount());
        assertTrue(r.getCostMs() >= 0);

        // Q/A 不切分：每个文档恰好 1 个 chunk
        assertEquals(1, store.findChunks(store.docIdOf("ai_knowledge", 1L)).size());
        // 长文本被切分
        assertTrue(store.findChunks(store.docIdOf("life_dormitory", 9L)).size() > 1);

        // urlPath 与 sourceId 契约
        KbDocument d = store.docs.get(store.docIdOf("life_dormitory", 9L));
        assertEquals("/life/dormitory", d.getUrlPath());
        assertEquals(9L, d.getSourceId());
        assertEquals(1, d.getStatus());
        // search_terms 只落到关键词路径，不污染正文
        KbChunk qaChunk = store.findChunks(store.docIdOf("ai_knowledge", 1L)).get(0);
        assertEquals("k1,k2", qaChunk.getSearchTerms());
        assertFalse(qaChunk.getContent().contains("k1,k2"), "search_terms 绝不能拼进正文");
    }

    @Test
    void urlPathTemplateIsExpandedWithSourceId() {
        FakeSource news = new FakeSource("sys_news", true,
                List.of(new DocumentSource.RawDocument(42L, "标题", "正文内容。", null, "新闻公告")),
                "/news/{id}");
        indexer(news).rebuildAll(false);
        assertEquals("/news/42", store.docs.get(store.docIdOf("sys_news", 42L)).getUrlPath());
    }

    @Test
    void nullSourceIdRowIsSkipped() {
        FakeSource src = new FakeSource("ai_knowledge", false,
                List.of(new DocumentSource.RawDocument(null, "坏行", "正文", null, null)));
        IndexReport r = indexer(src).rebuildAll(false);
        assertEquals(0, r.getDocumentCount(), "sourceId 为 null 的行必须被跳过（唯一键不阻止 NULL 重复行）");
        assertTrue(store.docs.isEmpty());
    }

    @Test
    void blankBodyIsNotIndexed() {
        FakeSource src = new FakeSource("ai_knowledge", false,
                List.of(new DocumentSource.RawDocument(1L, "空", "   ", null, null)));
        IndexReport r = indexer(src).rebuildAll(false);
        assertEquals(0, r.getDocumentCount());
        assertTrue(store.docs.isEmpty());
    }

    // ==================== 4. search_terms 截断契约 ====================

    @Test
    void searchTermsTruncatedAtCommaBoundary() {
        String longTerms = "甲".repeat(600) + "," + "乙".repeat(600);   // 1201 字符
        String out = KnowledgeIndexer.truncateSearchTerms(longTerms);
        assertNotNull(out);
        assertTrue(out.length() <= 1000, "必须截断到 1000 以内，实际 " + out.length());
        assertFalse(out.endsWith(","), "不应以逗号结尾");
        assertEquals("甲".repeat(600), out, "应在逗号边界切断");

        assertNull(KnowledgeIndexer.truncateSearchTerms(null));
        assertEquals("短,词", KnowledgeIndexer.truncateSearchTerms("短,词"));
    }

    // ==================== 测试替身 ====================

    /** 可指定是否切分、以及 urlPath 模板的假来源 */
    private static class FakeSource extends AbstractDocumentSource {
        private final String type;
        private final boolean split;
        private final List<RawDocument> docs;
        private final String urlTemplate;

        FakeSource(String type, boolean split, List<RawDocument> docs) {
            this(type, split, docs, null);
        }

        FakeSource(String type, boolean split, List<RawDocument> docs, String urlTemplate) {
            this.type = type;
            this.split = split;
            this.docs = docs;
            this.urlTemplate = urlTemplate;
        }

        @Override public String sourceType() { return type; }
        @Override public String urlPathTemplate() { return urlTemplate; }
        @Override public boolean needsSplitting() { return split; }
        @Override public List<RawDocument> extract() { return docs; }
    }

    /** 内存版知识库：让增量复用/删除顺序等有状态行为可被真实验证 */
    private static class FakeKbStore implements KbStore {
        final Map<Long, KbDocument> docs = new LinkedHashMap<>();
        final Map<Long, List<KbChunk>> chunkMap = new LinkedHashMap<>();
        private long seq = 0;

        Long docIdOf(String type, Long sourceId) {
            return docs.values().stream()
                    .filter(d -> type.equals(d.getSourceType()) && sourceId.equals(d.getSourceId()))
                    .map(KbDocument::getId).findFirst().orElse(null);
        }

        @Override
        public Optional<KbDocument> findDocument(String sourceType, Long sourceId) {
            return docs.values().stream()
                    .filter(d -> sourceType.equals(d.getSourceType()) && sourceId.equals(d.getSourceId()))
                    .findFirst().map(FakeKbStore::copy);
        }

        @Override
        public Long saveDocument(KbDocument doc) {
            if (doc.getId() == null) {
                doc.setId(++seq);
            }
            docs.put(doc.getId(), copy(doc));
            return doc.getId();
        }

        @Override
        public List<KbChunk> findChunks(Long documentId) {
            return new ArrayList<>(chunkMap.getOrDefault(documentId, List.of()));
        }

        @Override
        public void deleteChunks(Long documentId) {
            chunkMap.remove(documentId);
        }

        @Override
        public void insertChunk(KbChunk chunk) {
            chunkMap.computeIfAbsent(chunk.getDocumentId(), k -> new ArrayList<>()).add(chunk);
        }

        @Override
        public void updateChunkCount(Long documentId, int chunkCount) {
            KbDocument d = docs.get(documentId);
            if (d != null) {
                d.setChunkCount(chunkCount);
            }
        }

        private static KbDocument copy(KbDocument d) {
            KbDocument c = new KbDocument();
            c.setId(d.getId());
            c.setSourceType(d.getSourceType());
            c.setSourceId(d.getSourceId());
            c.setTitle(d.getTitle());
            c.setCategory(d.getCategory());
            c.setUrlPath(d.getUrlPath());
            c.setContentHash(d.getContentHash());
            c.setChunkCount(d.getChunkCount());
            c.setStatus(d.getStatus());
            return c;
        }
    }

    /** 计数用假 EmbeddingClient：不联网，可让包含指定子串的文本失败 */
    private static class CountingEmbedder extends EmbeddingClient {
        final AtomicInteger batchCalls = new AtomicInteger();
        final Set<String> failIfContains = new HashSet<>();

        CountingEmbedder(RagProperties props) {
            super(props);
        }

        @Override
        public List<float[]> embedBatch(List<String> texts) {
            batchCalls.incrementAndGet();
            List<float[]> out = new ArrayList<>(texts.size());
            for (String t : texts) {
                boolean fail = failIfContains.stream().anyMatch(t::contains);
                out.add(fail ? null : new float[]{1f, 0f, 0f, 0f});
            }
            return out;
        }

        @Override
        public float[] embed(String text) {
            return embedBatch(List.of(text)).get(0);
        }
    }
}
