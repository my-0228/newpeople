package com.freshman.agent.tool;

import com.freshman.agent.AgentTool;
import com.freshman.agent.ToolContext;
import com.freshman.agent.ToolResult;
import com.freshman.rag.HybridRetriever;
import com.freshman.rag.dto.RetrievalResult;
import com.freshman.rag.dto.ScoredChunk;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

/**
 * SearchKnowledgeTool 单元测试（Mock 检索器，不连库、不调 API）。
 * 重点：topK 钳制、无覆盖时的语义（success=true）、以及**不把 rrfScore 泄漏给模型**。
 */
class SearchKnowledgeToolTest {

    private HybridRetriever retriever;
    private SearchKnowledgeTool tool;
    private final ToolContext ctx = new ToolContext("s", null, "127.0.0.1");
    private final AtomicInteger lastTopK = new AtomicInteger();

    @BeforeEach
    void setUp() {
        retriever = mock(HybridRetriever.class);
        tool = new SearchKnowledgeTool(retriever);
        when(retriever.retrieve(any(), anyInt())).thenAnswer(inv -> {
            lastTopK.set(inv.getArgument(1));
            return result(List.of());
        });
    }

    private static ScoredChunk material(long chunkId, String title, String urlPath, String snippet,
                                       Double cosine, Double keyword) {
        ScoredChunk c = new ScoredChunk();
        c.setChunkId(chunkId);
        c.setDocumentId(100L + chunkId);
        c.setChunkIndex(0);
        c.setTitle(title);
        c.setUrlPath(urlPath);
        c.setContent(snippet);
        c.setSnippet(snippet);
        c.setSourceType("guide_faq");
        c.setSourceId(chunkId);
        c.setVectorCosine(cosine);
        c.setKeywordScore(keyword);
        c.setRrfScore(0.0164);      // 故意设成 RRF 量级，用于验证它不会被投影出去
        return c;
    }

    private static RetrievalResult result(List<ScoredChunk> chunks) {
        RetrievalResult rr = new RetrievalResult();
        rr.setChunks(new ArrayList<>(chunks));
        rr.setHasQualifiedMaterial(!chunks.isEmpty());
        rr.setGateMode("vector");
        rr.setTopScore(chunks.isEmpty() ? 0 : 0.78);
        return rr;
    }

    @Test
    void nameAndSchemaAreValid() {
        assertEquals("search_knowledge", tool.name());
        assertEquals("object", tool.parameters().get("type"));
        assertTrue(tool.parameters().containsKey("properties"));
    }

    @Test
    void topKIsClampedTo1Through10() {
        tool.execute(Map.of("query", "宿舍"), ctx);
        assertEquals(3, lastTopK.get(), "未传 top_k 时默认 3");

        tool.execute(Map.of("query", "宿舍", "top_k", 0), ctx);
        assertEquals(1, lastTopK.get(), "0 应被钳制为 1");

        tool.execute(Map.of("query", "宿舍", "top_k", -5), ctx);
        assertEquals(1, lastTopK.get());

        tool.execute(Map.of("query", "宿舍", "top_k", 99), ctx);
        assertEquals(10, lastTopK.get(), "超大值应被钳制为 10");

        tool.execute(Map.of("query", "宿舍", "top_k", 5), ctx);
        assertEquals(5, lastTopK.get());

        tool.execute(Map.of("query", "宿舍", "top_k", "abc"), ctx);
        assertEquals(3, lastTopK.get(), "非法类型应回退默认值");
    }

    @Test
    void missingQueryFailsWithClearMessage() {
        ToolResult r = tool.execute(Map.of(), ctx);
        assertFalse(r.success());
        assertTrue(r.content().contains("query"));
        verify(retriever, never()).retrieve(any(), anyInt());
    }

    /** 关键：无覆盖时必须是 success=true，否则模型会当成系统故障而改用自身记忆编造 */
    @Test
    void noQualifiedMaterialReturnsSuccessWithHonestInstruction() {
        when(retriever.retrieve(any(), anyInt())).thenReturn(result(List.of()));

        ToolResult r = tool.execute(Map.of("query", "学校有没有高尔夫球场"), ctx);

        assertTrue(r.success(), "无覆盖不是工具故障，必须 success=true 让模型区分二者");
        assertTrue(r.content().contains("知识库未覆盖"), "应明确告知未覆盖：" + r.content());
        assertTrue(r.content().contains("不要") && r.content().contains("编造"),
                "应显式禁止编造：" + r.content());
        assertEquals(Boolean.FALSE, r.meta().get("hasMaterial"));
    }

    @Test
    void materialsAreProjectedAndRrfScoreIsNeverExposed() {
        when(retriever.retrieve(any(), anyInt())).thenReturn(result(List.of(
                material(1L, "宿舍有空调吗", "/guide/faq", "厚德学区配备空调", 0.78, null),
                material(2L, "宿舍物业费", "/life/dormitory", "厚德学区 1200 元/年", 0.71, null))));

        ToolResult r = tool.execute(Map.of("query", "宿舍", "top_k", 2), ctx);

        assertTrue(r.success());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> materials = (List<Map<String, Object>>) r.meta().get("materials");
        assertNotNull(materials);
        assertEquals(2, materials.size());

        Map<String, Object> first = materials.get(0);
        assertEquals(1, first.get("index"));
        assertEquals("宿舍有空调吗", first.get("title"));
        assertEquals("/guide/faq", first.get("url_path"));
        assertEquals("厚德学区配备空调", first.get("snippet"));
        assertEquals("guide_faq", first.get("source_type"));

        // rrfScore 只用于排序，绝不能出现在回填给模型的任何地方
        String whole = r.content() + materials.toString();
        assertFalse(whole.contains("rrf"), "不得出现 rrf 相关字段：" + whole);
        assertFalse(whole.contains("0.0164"), "不得把 RRF 分数值泄漏给模型：" + whole);

        // 相似度应是原始分（0.78），而不是 RRF 分
        assertTrue(r.content().contains("0.78"), "应展示原始相似度：" + r.content());
    }

    @Test
    void retrievalExceptionBecomesFailureNotThrow() {
        when(retriever.retrieve(any(), anyInt())).thenThrow(new IllegalStateException("索引不可用"));

        ToolResult r = assertDoesNotThrow(() -> tool.execute(Map.of("query", "宿舍"), ctx));
        assertFalse(r.success());
        assertTrue(r.content().contains("检索失败"));
    }

    @Test
    void keywordGateModeShowsKeywordScore() {
        RetrievalResult kw = result(List.of(material(1L, "标题", null, "摘要", null, 0.62)));
        kw.setGateMode("keyword");
        when(retriever.retrieve(any(), anyInt())).thenReturn(kw);

        ToolResult r = tool.execute(Map.of("query", "宿舍"), ctx);

        assertTrue(r.content().contains("0.62"), "keyword 模式应展示 keywordScore：" + r.content());
    }

    @Test
    void defaultTopKPassedToRetriever() {
        ArgumentCaptor<Integer> captor = ArgumentCaptor.forClass(Integer.class);
        tool.execute(Map.of("query", "宿舍"), ctx);
        verify(retriever, times(1)).retrieve(any(), captor.capture());
        assertEquals(3, captor.getValue());
    }
}
