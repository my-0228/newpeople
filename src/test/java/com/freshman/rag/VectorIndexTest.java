package com.freshman.rag;

import com.freshman.entity.KbChunk;
import com.freshman.entity.KbDocument;
import com.freshman.mapper.KbChunkMapper;
import com.freshman.mapper.KbDocumentMapper;
import com.freshman.rag.dto.ScoredChunk;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * VectorIndex 单元测试（Mockito 模拟 Mapper，不连数据库、不启动 Spring 上下文）。
 * 覆盖：点积=余弦、Top-K 降序、只加载 status=1、脏数据跳过、空索引不抛异常、重建原子换引用。
 */
class VectorIndexTest {

    private KbChunkMapper chunkMapper;
    private KbDocumentMapper documentMapper;
    private RagProperties props;
    private VectorIndex index;

    @BeforeEach
    void setUp() {
        chunkMapper = mock(KbChunkMapper.class);
        documentMapper = mock(KbDocumentMapper.class);
        props = new RagProperties();
        props.getEmbedding().setDimensions(4);
        index = new VectorIndex(chunkMapper, documentMapper, props);
    }

    private static KbChunk chunk(long id, long docId, int idx, Integer status, String embedding) {
        KbChunk c = new KbChunk();
        c.setId(id);
        c.setDocumentId(docId);
        c.setChunkIndex(idx);
        c.setContent("正文内容-" + id);
        c.setStatus(status);
        c.setEmbedding(embedding);
        c.setDim(4);
        return c;
    }

    private static KbDocument document(long id) {
        KbDocument d = new KbDocument();
        d.setId(id);
        d.setTitle("标题-" + id);
        d.setSourceType("ai_knowledge");
        d.setSourceId(id);
        d.setUrlPath("/guide/x");
        d.setStatus(1);          // 必须为启用态，否则索引会按"文档缺失/禁用"剔除其 chunk
        return d;
    }

    private void stubChunks(List<KbChunk> chunks) {
        when(chunkMapper.selectList(any())).thenReturn(chunks);
        when(documentMapper.selectBatchIds(any())).thenReturn(List.of(document(10L), document(11L)));
    }

    @Test
    void dotProductEqualsCosineForNormalizedVectors() {
        stubChunks(List.of(
                chunk(1L, 10L, 0, 1, "[1,0,0,0]"),
                chunk(2L, 10L, 1, 1, "[0,1,0,0]")));
        index.rebuild();
        assertEquals(2, index.size());

        List<ScoredChunk> r = index.search(new float[]{1f, 0f, 0f, 0f}, 2);

        assertEquals(2, r.size());
        assertEquals(1L, r.get(0).getChunkId());
        assertEquals(1.0, r.get(0).getVectorCosine(), 1e-6, "同向单位向量余弦应为 1");
        assertEquals(0.0, r.get(1).getVectorCosine(), 1e-6, "正交单位向量余弦应为 0");
    }

    @Test
    void returnsTopKInDescendingOrder() {
        stubChunks(List.of(
                chunk(1L, 10L, 0, 1, "[1,0,0,0]"),      // 余弦 1.0
                chunk(2L, 10L, 1, 1, "[0.6,0.8,0,0]"),  // 余弦 0.6
                chunk(3L, 10L, 2, 1, "[0,0,1,0]")));    // 余弦 0.0
        index.rebuild();

        List<ScoredChunk> r = index.search(new float[]{1f, 0f, 0f, 0f}, 3);

        assertEquals(3, r.size());
        assertEquals(1L, r.get(0).getChunkId());
        assertEquals(2L, r.get(1).getChunkId());
        assertEquals(3L, r.get(2).getChunkId());
        assertTrue(r.get(0).getVectorCosine() >= r.get(1).getVectorCosine());
        assertTrue(r.get(1).getVectorCosine() >= r.get(2).getVectorCosine());
    }

    @Test
    void topKHonoursRequestedLimit() {
        stubChunks(List.of(
                chunk(1L, 10L, 0, 1, "[1,0,0,0]"),
                chunk(2L, 10L, 1, 1, "[0.6,0.8,0,0]"),
                chunk(3L, 10L, 2, 1, "[0,0,1,0]")));
        index.rebuild();

        assertEquals(1, index.search(new float[]{1f, 0f, 0f, 0f}, 1).size());
        assertEquals(2, index.search(new float[]{1f, 0f, 0f, 0f}, 2).size());
        assertEquals(3, index.search(new float[]{1f, 0f, 0f, 0f}, 10).size(), "topK 超过索引条数时应返回全部");
    }

    @Test
    void onlyRowWithStatusOneIsLoaded() {
        // 三行分别是 status=0/1/2，即使查询条件被改动，也只有 status=1 进索引（防御性过滤）
        stubChunks(List.of(
                chunk(1L, 10L, 0, 0, "[1,0,0,0]"),
                chunk(2L, 10L, 1, 1, "[1,0,0,0]"),
                chunk(3L, 10L, 2, 2, "[1,0,0,0]")));
        index.rebuild();

        assertEquals(1, index.size());
        assertEquals(2L, index.search(new float[]{1f, 0f, 0f, 0f}, 5).get(0).getChunkId());
    }

    @Test
    void nullOrDirtyEmbeddingRowIsSkippedWithoutFailing() {
        stubChunks(List.of(
                chunk(1L, 10L, 0, 1, null),            // 空向量
                chunk(2L, 10L, 1, 1, "  "),            // 空白向量
                chunk(3L, 10L, 2, 1, "not-json"),      // 非 JSON
                chunk(4L, 10L, 3, 1, "[1,0]"),         // 维度不符（期望 4）
                chunk(5L, 10L, 4, 1, "[1,0,0,0]")));   // 唯一合法
        assertDoesNotThrow(() -> index.rebuild());

        assertEquals(1, index.size(), "脏数据应被跳过而不是导致重建失败");
        assertEquals(5L, index.search(new float[]{1f, 0f, 0f, 0f}, 5).get(0).getChunkId());
    }

    @Test
    void emptyIndexReturnsEmptyListNotException() {
        stubChunks(List.of());
        index.rebuild();

        assertTrue(index.isEmpty());
        assertEquals(0, index.size());
        List<ScoredChunk> r = assertDoesNotThrow(() -> index.search(new float[]{1f, 0f, 0f, 0f}, 5));
        assertTrue(r.isEmpty(), "空索引应返回空列表（上层据此转 gate_mode=keyword）");
    }

    @Test
    void chunksWhoseDocumentIsMissingOrDisabledAreDropped() {
        // chunk 1 的文档存在且启用；chunk 2 的文档不存在；chunk 3 的文档被禁用
        when(chunkMapper.selectList(any())).thenReturn(List.of(
                chunk(1L, 10L, 0, 1, "[1,0,0,0]"),
                chunk(2L, 99L, 0, 1, "[1,0,0,0]"),   // 文档 99 查不到
                chunk(3L, 11L, 0, 1, "[1,0,0,0]"))); // 文档 11 被禁用
        KbDocument disabled = document(11L);
        disabled.setStatus(0);
        when(documentMapper.selectBatchIds(any())).thenReturn(List.of(document(10L), disabled));

        index.rebuild();

        assertEquals(1, index.size(), "文档缺失或禁用的 chunk 不应留在索引里（否则会给幽灵答案）");
        assertEquals(1L, index.search(new float[]{1f, 0f, 0f, 0f}, 5).get(0).getChunkId());
    }

    @Test
    void dimensionMismatchedQueryIsRejectedInsteadOfScored() {
        stubChunks(List.of(chunk(1L, 10L, 0, 1, "[1,0,0,0]")));
        index.rebuild();

        // 查询维度 2 ≠ 索引维度 4 → 应返回空而不是用 Math.min 静默算出一个分
        assertTrue(index.search(new float[]{1f, 0f}, 5).isEmpty(),
                "维度不匹配的查询必须被拒绝");
    }

    @Test
    void rebuildAtomicallySwapsSnapshot() {
        stubChunks(List.of(chunk(1L, 10L, 0, 1, "[1,0,0,0]")));
        index.rebuild();
        assertEquals(1, index.size());
        assertEquals(1L, index.search(new float[]{1f, 0f, 0f, 0f}, 5).get(0).getChunkId());

        // 第二次重建换成不同数据：旧条目必须彻底消失，证明是整体换引用而非原地修改
        when(chunkMapper.selectList(any())).thenReturn(List.of(
                chunk(20L, 11L, 0, 1, "[1,0,0,0]"),
                chunk(21L, 11L, 1, 1, "[1,0,0,0]")));
        when(documentMapper.selectBatchIds(any())).thenReturn(List.of(document(11L)));
        index.rebuild();

        assertEquals(2, index.size());
        List<ScoredChunk> r = index.search(new float[]{1f, 0f, 0f, 0f}, 5);
        assertEquals(2, r.size());
        assertTrue(r.stream().noneMatch(c -> c.getChunkId() == 1L), "旧快照的条目不应残留");
        assertEquals("标题-11", r.get(0).getTitle(), "文档元信息应随新快照一起生效");
        assertEquals("/guide/x", r.get(0).getUrlPath());
    }
}
