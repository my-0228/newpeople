package com.freshman.rag;

import com.freshman.rag.dto.RetrievalResult;
import com.freshman.rag.dto.ScoredChunk;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

/**
 * HybridRetriever 单元测试 —— **本计划最关键的一组断言**。
 *
 * 它把"RRF 只排序、门限只看当前模式的原始分、空值候选不进材料集"这三条语义钉死。
 * 若实现退化成"拿 rrfScore 比 0.35 门限"，第一条测试就会红 —— 而这种退化会让
 * 整个 RAG 改造**静默失效**（永远拒答）。
 */
class HybridRetrieverTest {

    private VectorIndex vectorIndex;
    private KeywordRetriever keywordRetriever;
    private EmbeddingClient embedder;
    private RagProperties props;
    private HybridRetriever retriever;

    @BeforeEach
    void setUp() {
        vectorIndex = mock(VectorIndex.class);
        keywordRetriever = mock(KeywordRetriever.class);
        embedder = mock(EmbeddingClient.class);
        props = new RagProperties();
        props.setTopKVector(20);
        props.setTopKKeyword(20);
        props.setMinScore(0.35);
        props.setKeywordMinScore(0.25);
        props.setRelativeFloor(0.6);
        props.setRrfK(60);
        props.setMaxPerDocument(2);
        retriever = new HybridRetriever(vectorIndex, keywordRetriever, embedder, props);
    }

    private static ScoredChunk chunk(long id, long docId, Double vectorCosine, Double keywordScore) {
        ScoredChunk c = new ScoredChunk();
        c.setChunkId(id);
        c.setDocumentId(docId);
        c.setChunkIndex(0);
        c.setTitle("标题" + id);
        c.setContent("正文" + id);
        c.setSnippet("正文" + id);
        c.setSourceType("ai_knowledge");
        c.setSourceId(docId);
        c.setVectorCosine(vectorCosine);
        c.setKeywordScore(keywordScore);
        return c;
    }

    /** 让向量路径可用：索引非空 + embedding 成功 */
    private void vectorAvailable(List<ScoredChunk> hits) {
        when(vectorIndex.isEmpty()).thenReturn(false);
        when(embedder.embed(any())).thenReturn(new float[]{1f, 0f, 0f, 0f});
        when(vectorIndex.search(any(), anyInt())).thenReturn(hits);
    }

    private void keywordReturns(List<ScoredChunk> hits) {
        when(keywordRetriever.search(any(), anyInt())).thenReturn(hits);
    }

    // ==================== 1. 最致命的一条：RRF 不得参与门限 ====================

    @Test
    void rrfIsUsedForOrderingOnlyNeverForGating() {
        // 向量 top1 余弦 0.9（远超 min-score 0.35）→ 必须判定为"有材料"
        vectorAvailable(List.of(chunk(1L, 10L, 0.9, null)));
        keywordReturns(List.of());

        RetrievalResult r = retriever.retrieve("宿舍有空调吗", 5);

        assertTrue(r.hasQualifiedMaterial(),
                "向量原始余弦 0.9 远超门限 0.35，必须判定有材料。"
                        + "若实现拿 RRF 分（≈0.016）比 0.35，这里会失败 —— 那正是会静默失效的退化");
        assertEquals("vector", r.getGateMode());
        assertEquals(1, r.getChunks().size());
        // topScore 必须是原始余弦（≈0.9），不是 RRF 分（≈0.016）
        assertEquals(0.9, r.getTopScore(), 1e-6, "topScore 必须是原始分，不是 RRF 分");
        assertTrue(r.getChunks().get(0).getRrfScore() > 0 && r.getChunks().get(0).getRrfScore() < 0.05,
                "RRF 分只用于排序，量级应在 0.016 附近");
    }

    // ==================== 2. vector 模式绝对门限 ====================

    @Test
    void vectorGateRejectsWhenTop1BelowMinScore() {
        // 向量 top1 仅 0.20 < 0.35；关键词路径即使有 0.9 也不回退（不回退是关键）
        vectorAvailable(List.of(chunk(1L, 10L, 0.20, 0.9)));
        keywordReturns(List.of(chunk(1L, 10L, null, 0.9)));

        RetrievalResult r = retriever.retrieve("无关问题", 5);

        assertFalse(r.hasQualifiedMaterial(), "vector 模式下 top1 低于 min-score 应拒答");
        assertTrue(r.getChunks().isEmpty());
        assertEquals("vector", r.getGateMode(), "不应回退到 keyword 门限");
        assertEquals(0.20, r.getTopScore(), 1e-6);
    }

    // ==================== 3. 相对门限砍尾巴 ====================

    @Test
    void relativeGateDropsTailCandidates() {
        vectorAvailable(List.of(
                chunk(1L, 10L, 0.80, null),
                chunk(2L, 11L, 0.30, null)));   // 0.30 < 0.6 × 0.80 = 0.48 → 应被剔除
        keywordReturns(List.of());

        RetrievalResult r = retriever.retrieve("宿舍", 5);

        assertTrue(r.hasQualifiedMaterial());
        assertEquals(1, r.getChunks().size(), "落后于相对门限的候选应被剔除");
        assertEquals(1L, r.getChunks().get(0).getChunkId());
    }

    // ==================== 4. 空值候选规则 ====================

    @Test
    void keywordOnlyCandidateNeverEntersMaterialSetInVectorMode() {
        // chunk 1 向量余弦 0.8（合格）；chunk 2 仅被关键词命中（vectorCosine=null）
        vectorAvailable(List.of(chunk(1L, 10L, 0.8, null)));
        keywordReturns(List.of(
                chunk(1L, 10L, null, 0.5),
                chunk(2L, 11L, null, 0.95)));

        RetrievalResult r = retriever.retrieve("宿舍", 5);

        assertEquals("vector", r.getGateMode());
        assertEquals(1, r.getChunks().size(),
                "gateMode=vector 时，没有 vectorCosine 的候选不得进材料集（" + r.getChunks() + "）");
        assertEquals(1L, r.getChunks().get(0).getChunkId());
        assertEquals(2, r.getKeywordHits(), "关键词候选仍应计入日志计数");
    }

    // ==================== 5. 向量不可用时退回 keyword 门限 ====================

    @Test
    void fallsBackToKeywordGateWhenVectorIndexIsEmpty() {
        when(vectorIndex.isEmpty()).thenReturn(true);
        keywordReturns(List.of(chunk(1L, 10L, null, 0.62)));

        RetrievalResult r = retriever.retrieve("宿舍有空调吗", 5);

        assertEquals("keyword", r.getGateMode());
        assertTrue(r.hasQualifiedMaterial(), "0.62 > keyword-min-score 0.25");
        assertEquals(0.62, r.getTopScore(), 1e-6, "topScore 应为 keywordScore");
        assertNull(r.getChunks().get(0).getVectorCosine());
        verify(embedder, never()).embed(any());
    }

    @Test
    void keywordGateRejectsWhenTop1BelowKeywordMinScore() {
        when(vectorIndex.isEmpty()).thenReturn(true);
        keywordReturns(List.of(chunk(1L, 10L, null, 0.10)));

        RetrievalResult r = retriever.retrieve("完全无关的问题", 5);

        assertFalse(r.hasQualifiedMaterial());
        assertEquals("keyword", r.getGateMode());
    }

    // ==================== 6. 同文档去重 ====================

    @Test
    void sameDocumentChunksAreDeduplicated() {
        // 同一文档 3 块都过门限，max-per-document=2 → 材料集最多 2 块
        vectorAvailable(List.of(
                chunk(1L, 10L, 0.9, null),
                chunk(2L, 10L, 0.8, null),
                chunk(3L, 10L, 0.7, null)));
        keywordReturns(List.of());

        RetrievalResult r = retriever.retrieve("宿舍", 5);

        assertEquals(2, r.getChunks().size(), "同一文档最多保留 max-per-document 条");
    }

    // ==================== 7. 全空安全 ====================

    @Test
    void emptyEverythingYieldsNoMaterialWithoutException() {
        when(vectorIndex.isEmpty()).thenReturn(true);
        keywordReturns(List.of());

        RetrievalResult r = assertDoesNotThrow(() -> retriever.retrieve("任何问题", 5));

        assertFalse(r.hasQualifiedMaterial());
        assertTrue(r.getChunks().isEmpty());
        assertEquals(0.0, r.getTopScore(), 1e-9);
        assertEquals("keyword", r.getGateMode());
    }

    // ==================== 8. topK 语义：尊重传入值 ====================

    @Test
    void topKIsHonouredAndClampedToConfigUpperBound() {
        List<ScoredChunk> hits = new ArrayList<>();
        for (int i = 1; i <= 6; i++) {
            hits.add(chunk(i, 100L + i, 0.9 - i * 0.01, null));   // 每条属不同文档，避免去重影响
        }
        vectorAvailable(hits);
        keywordReturns(List.of());

        assertEquals(2, retriever.retrieve("宿舍", 2).getChunks().size());
        assertEquals(4, retriever.retrieve("宿舍", 4).getChunks().size());

        props.setTopKVector(3);
        assertEquals(3, retriever.retrieve("宿舍", 10).getChunks().size(),
                "topK 应被钳制到 top-k-vector");
    }
}
