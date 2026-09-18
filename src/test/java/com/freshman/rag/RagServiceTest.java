package com.freshman.rag;

import com.freshman.entity.AiRetrievalLog;
import com.freshman.mapper.AiRetrievalLogMapper;
import com.freshman.rag.dto.RetrievalResult;
import com.freshman.rag.dto.ScoredChunk;
import com.freshman.rag.llm.LlmClient;
import com.freshman.rag.llm.LlmResult;
import com.freshman.service.AiQaService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * RagService 单元测试。
 *
 * 三条硬性行为的回归保护：
 *  ① **拒答不调用 LLM**（省钱 + 零幻觉）—— 用计数实现断言调用次数为 0
 *  ② **幻觉引用被剔除并标降级** —— 引用了不存在的材料编号
 *  ③ LLM 失败时降级到本地引擎，且答案与引用可解释
 */
class RagServiceTest {

    private HybridRetriever retriever;
    private RecordingLlmClient llm;
    private StubLocalAnswerProvider local;
    private AiRetrievalLogMapper logMapper;
    private RagProperties props;
    private RagService service;

    @BeforeEach
    void setUp() {
        retriever = mock(HybridRetriever.class);
        llm = new RecordingLlmClient();
        local = new StubLocalAnswerProvider();
        logMapper = mock(AiRetrievalLogMapper.class);
        props = new RagProperties();
        props.setTopKFinal(5);
        service = new RagService(retriever, llm, local, logMapper, props);
    }

    private static ScoredChunk material(long chunkId, long docId, String title, String urlPath,
                                       Double vectorCosine, Double keywordScore) {
        ScoredChunk c = new ScoredChunk();
        c.setChunkId(chunkId);
        c.setDocumentId(docId);
        c.setChunkIndex(0);
        c.setTitle(title);
        c.setUrlPath(urlPath);
        c.setContent("材料正文" + chunkId);
        c.setSnippet("材料正文" + chunkId);
        c.setSourceType("ai_knowledge");
        c.setSourceId(docId);
        c.setVectorCosine(vectorCosine);
        c.setKeywordScore(keywordScore);
        return c;
    }

    private void retrievalWith(List<ScoredChunk> materials, String gateMode, double topScore) {
        RetrievalResult rr = new RetrievalResult();
        rr.setChunks(materials);
        rr.setHasQualifiedMaterial(!materials.isEmpty());
        rr.setGateMode(gateMode);
        rr.setTopScore(topScore);
        rr.setVectorHits(materials.size());
        rr.setKeywordHits(0);
        rr.setEmbeddingMs(12);
        when(retriever.retrieve(any(), anyInt())).thenReturn(rr);
    }

    private void noQualifiedMaterial() {
        RetrievalResult rr = new RetrievalResult();
        rr.setChunks(new ArrayList<>());
        rr.setHasQualifiedMaterial(false);
        rr.setGateMode("vector");
        rr.setTopScore(0.12);
        rr.setVectorHits(2);
        rr.setKeywordHits(1);
        rr.setEmbeddingMs(9);
        when(retriever.retrieve(any(), anyInt())).thenReturn(rr);
    }

    // ==================== ① 拒答不调用 LLM ====================

    @Test
    void refusalPathNeverCallsLlm() {
        noQualifiedMaterial();

        AiQaService.ChatResponse resp = service.ask("学校有没有高尔夫球场", "s-1");

        assertEquals(0, llm.calls.get(),
                "无合格材料时必须直接拒答，绝不能调用 LLM（省钱且零幻觉）");
        assertTrue(resp.getIsUnknown());
        assertTrue(resp.getCitations().isEmpty());
        assertEquals(Boolean.FALSE, resp.getDegraded());

        // 可核查证据：拒答路径的 generate_ms 必须是 NULL
        ArgumentCaptor<AiRetrievalLog> captor = ArgumentCaptor.forClass(AiRetrievalLog.class);
        verify(logMapper, times(1)).insert(captor.capture());
        AiRetrievalLog logged = captor.getValue();
        assertNull(logged.getGenerateMs(), "generate_ms IS NULL 是'未调用 LLM'的核查依据");
        assertEquals(1, logged.getIsUnknown());
        assertEquals("vector", logged.getGateMode());
    }

    // ==================== ② 引用解析 ====================

    @Test
    void citationsAreParsedAndMappedToMaterials() {
        retrievalWith(List.of(
                material(1L, 10L, "宿舍有空调吗", null, 0.91, null),
                material(2L, 11L, "厚德学区物业费", "/life/dormitory", 0.85, null)), "vector", 0.91);
        llm.answer = "厚德学区（H1-H4）宿舍配备空调 [1]，其他学区暂未安装。物业费 1200 元/年 [2]。";

        AiQaService.ChatResponse resp = service.ask("宿舍有空调吗", "s-2");

        assertEquals(2, resp.getCitations().size());
        assertEquals(1, resp.getCitations().get(0).getIndex());
        assertEquals("宿舍有空调吗", resp.getCitations().get(0).getTitle());
        assertEquals(2, resp.getCitations().get(1).getIndex());
        assertEquals("/life/dormitory", resp.getCitations().get(1).getUrlPath());
        assertNotNull(resp.getCitations().get(0).getSnippet());
        assertEquals(0.91, resp.getCitations().get(0).getScore(), 1e-6, "引用分数应是原始分");
        assertEquals(Boolean.FALSE, resp.getDegraded());
    }

    @Test
    void hallucinatedCitationIndexIsDroppedAndMarksDegraded() {
        // 只有 2 条材料，模型却引用了 [7]（还有一条合法引用 [1]）
        retrievalWith(List.of(
                material(1L, 10L, "标题A", null, 0.9, null),
                material(2L, 11L, "标题B", null, 0.8, null)), "vector", 0.9);
        llm.answer = "根据材料 [1]，答案是……另外参考 [7]。";

        AiQaService.ChatResponse resp = service.ask("问题", "s-3");

        assertEquals(1, resp.getCitations().size(), "只应保留真实存在的引用");
        assertEquals(1, resp.getCitations().get(0).getIndex());
        assertEquals(Boolean.TRUE, resp.getDegraded(), "出现幻觉引用应标记降级");
    }

    // ==================== ③ 降级 ====================

    @Test
    void llmFailureFallsBackToLocalEngineAndMarksDegraded() {
        retrievalWith(List.of(material(1L, 10L, "标题A", null, 0.9, null)), "vector", 0.9);
        llm.failWith = "账户余额不足";
        local.answer = "本地引擎的答案";

        AiQaService.ChatResponse resp = service.ask("宿舍有空调吗", "s-4");

        assertEquals(Boolean.TRUE, resp.getDegraded());
        assertTrue(resp.getAnswer().contains("本地引擎的答案"), "降级答案应来自本地引擎");
        assertTrue(resp.getAnswer().contains("离线降级"), "降级答案应有前缀标识");
        assertTrue(resp.getCitations().isEmpty(), "降级路径没有引用");
    }

    @Test
    void llmNotConfiguredAlsoDegradesWithoutCallingIt() {
        retrievalWith(List.of(material(1L, 10L, "标题A", null, 0.9, null)), "vector", 0.9);
        llm.configured = false;

        AiQaService.ChatResponse resp = service.ask("问题", "s-5");

        assertEquals(0, llm.calls.get(), "未配置时不应发起调用");
        assertEquals(Boolean.TRUE, resp.getDegraded());
    }

    // ==================== ④ prompt 元信息 ====================

    @Test
    void promptCarriesMaterialMetadataWithRawScoreNotRrf() {
        retrievalWith(List.of(
                material(1L, 10L, "宿舍有空调吗", null, 0.91, null)), "vector", 0.91);
        llm.answer = "答案 [1]";

        service.ask("宿舍有空调吗", "s-6");

        String prompt = llm.lastSystemPrompt;
        assertNotNull(prompt);
        assertTrue(prompt.contains("【材料1｜来源：宿舍有空调吗｜相似度：0.91】"),
                "prompt 材料块应带来源与原始分，实际：" + prompt);
        assertTrue(prompt.contains("只依据下面提供的参考材料回答"), "应包含严格的材料约束指令");
        assertTrue(prompt.contains("知识库暂未收录"), "应指示模型在材料不足时明说");
    }

    @Test
    void keywordGateModeShowsKeywordScoreInPrompt() {
        retrievalWith(List.of(material(1L, 10L, "标题A", null, null, 0.62)), "keyword", 0.62);
        llm.answer = "答案 [1]";

        service.ask("问题", "s-7");

        assertTrue(llm.lastSystemPrompt.contains("相似度：0.62"),
                "keyword 模式应展示 keywordScore");
    }

    // ==================== ⑤ 日志分段耗时 ====================

    @Test
    void retrievalLogRecordsGateModeAndSegmentTimings() {
        retrievalWith(List.of(material(1L, 10L, "标题A", null, 0.93, null)), "vector", 0.93);
        llm.answer = "答案 [1]";

        service.ask("问题", "s-8");

        ArgumentCaptor<AiRetrievalLog> captor = ArgumentCaptor.forClass(AiRetrievalLog.class);
        verify(logMapper, times(1)).insert(captor.capture());
        AiRetrievalLog logged = captor.getValue();
        assertEquals("vector", logged.getGateMode());
        assertEquals(0, new java.math.BigDecimal("0.9300").compareTo(logged.getTopScore()));
        assertEquals(12, logged.getEmbeddingMs());
        assertNotNull(logged.getRetrievalMs());
        assertNotNull(logged.getGenerateMs(), "调用过 LLM 时 generate_ms 不应为 NULL");
        assertEquals("1", logged.getFinalChunkIds());
    }

    // ==================== 测试替身 ====================

    /** 计数 + 可脚本化的 LLM 客户端（不联网） */
    static class RecordingLlmClient implements LlmClient {
        final AtomicInteger calls = new AtomicInteger();
        String answer = "默认答案";
        String failWith = null;
        boolean configured = true;
        volatile String lastSystemPrompt;

        @Override
        public boolean isConfigured() {
            return configured;
        }

        @Override
        public LlmResult complete(String systemPrompt, String userMessage) {
            calls.incrementAndGet();
            lastSystemPrompt = systemPrompt;
            if (failWith != null) {
                return LlmResult.fail(failWith, 5);
            }
            return LlmResult.ok(answer, 100, 5);
        }
    }

    /** 桩本地引擎 */
    static class StubLocalAnswerProvider implements LocalAnswerProvider {
        String answer = null;

        @Override
        public Optional<LocalAnswer> best(String question) {
            if (answer == null) {
                return Optional.empty();
            }
            return Optional.of(new LocalAnswer(1L, answer, "宿舍", 0.42));
        }
    }
}
