package com.freshman.controller;

import com.freshman.agent.AgentOrchestrator;
import com.freshman.agent.AgentProperties;
import com.freshman.agent.dto.AgentAnswer;
import com.freshman.agent.dto.AgentChatResponse;
import com.freshman.agent.dto.AgentStep;
import com.freshman.common.Result;
import com.freshman.mapper.AgentToolCallLogMapper;
import com.freshman.service.AiQaService;
import com.freshman.service.AiQaService.ChatRequest;
import com.freshman.service.AiQaService.ChatResponse;
import com.freshman.service.DeepSeekChatService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

/**
 * {@code /api/deepseek/chat} 的三个分支行为与降级安全。
 *
 * 重点覆盖两类**只在真机才会暴露**的问题：
 * 1. 降级发生在工具步骤已落库之后时，响应里的 turnId 必须是编排器落库用的那个 ——
 *    否则前端拿它去 {@code /api/deepseek/trace} 回放会静默拿到 0 步（成功路径踩过同一个坑）。
 * 2. 未配 key / 开关关闭时，接口必须返回**正常结构**（degraded 或旧路径结果），
 *    而不是 error —— 这是「未配 key 不阻断使用」的 DoD 证据之一。
 */
@ExtendWith(MockitoExtension.class)
class DeepSeekControllerTest {

    @Mock private DeepSeekChatService deepSeekChatService;
    @Mock private AiQaService aiQaService;
    @Mock private AgentOrchestrator agentOrchestrator;
    @Mock private com.freshman.agent.ToolRegistry toolRegistry;
    @Mock private AgentToolCallLogMapper traceMapper;
    @Mock private com.freshman.service.CurrentUserResolver currentUserResolver;
    @Mock private HttpServletRequest httpRequest;
    @Mock private java.security.Principal principal;

    private AgentProperties props;
    private DeepSeekController controller;

    @BeforeEach
    void setUp() {
        props = new AgentProperties();
        props.setEnabled(true);
        controller = new DeepSeekController(deepSeekChatService, aiQaService,
                agentOrchestrator, props, toolRegistry, traceMapper, currentUserResolver);
    }

    private ChatRequest req(String q) {
        ChatRequest r = new ChatRequest();
        r.setQuestion(q);
        r.setSessionId("sess-1");
        return r;
    }

    private ChatResponse legacyAnswer(String text) {
        ChatResponse c = new ChatResponse();
        c.setQuestion("q");
        c.setAnswer(text);
        c.setConfidence(0.9);
        c.setCategory("报到");
        c.setIsUnknown(false);
        return c;
    }

    // ==================== 正常 Agent 路径 ====================

    @Test
    @DisplayName("Agent 正常应答：turnId 与轨迹步骤原样透出")
    void agentSuccessPassesThroughTurnIdAndSteps() {
        AgentAnswer a = new AgentAnswer();
        a.setTurnId("turn-success-1");
        a.setAnswer("宿舍有空调。");
        a.setStopReason("final_answer");
        a.setDegraded(false);
        a.setTotalMs(1234L);
        AgentStep s = new AgentStep();
        s.setStepNo(1);
        s.setToolName("query_campus_data");
        a.setSteps(List.of(s));
        when(agentOrchestrator.run(anyString(), anyString(), any())).thenReturn(a);

        Result<AgentChatResponse> r = controller.chat(req("宿舍有空调吗"), httpRequest, principal);

        assertEquals(200, r.getCode().intValue());
        assertNotNull(r.getData());
        assertEquals("turn-success-1", r.getData().getTurnId());
        assertEquals(1, r.getData().getSteps().size());
        assertFalse(r.getData().getDegraded());
        // 走 Agent 时不应再调用旧的无工具链路
        verify(deepSeekChatService, never()).chat(anyString(), anyString(), any(), any());
    }

    // ==================== 回归：降级 + 已有落库步骤 ====================

    @Test
    @DisplayName("回归：降级但已有工具步骤落库时，turnId 必须是编排器那个（否则轨迹回放为空）")
    void degradedWithPersistedStepsKeepsOrchestratorTurnId() {
        AgentAnswer a = new AgentAnswer();
        a.setTurnId("turn-degraded-9");
        a.setAnswer(null);
        a.setStopReason("degraded");
        a.setDegraded(true);
        AgentStep s = new AgentStep();
        s.setStepNo(1);
        s.setToolName("search_knowledge");
        a.setSteps(List.of(s));            // step1 已成功并落库
        when(agentOrchestrator.run(anyString(), anyString(), any())).thenReturn(a);
        when(currentUserResolver.resolveId(principal)).thenReturn(null);
        when(deepSeekChatService.chat(anyString(), anyString(), isNull(), isNull()))
                .thenReturn(legacyAnswer("降级后的本地答案"));

        Result<AgentChatResponse> r = controller.chat(req("军训服不合身怎么办"), httpRequest, principal);

        assertEquals(200, r.getCode().intValue(), "降级不是错误，不能返回 error");
        AgentChatResponse data = r.getData();
        assertTrue(data.getDegraded());
        assertEquals("降级后的本地答案", data.getAnswer());
        assertEquals(1, data.getSteps().size(), "已落库的步骤应透出");
        assertEquals("turn-degraded-9", data.getTurnId(),
                "turnId 必须复用编排器的，否则 /trace 查不回这 1 步");
    }

    @Test
    @DisplayName("降级且没有任何步骤时，turnId 可与编排器的不同（无轨迹可回放）")
    void degradedWithoutStepsStillReturnsOk() {
        AgentAnswer a = new AgentAnswer();
        a.setTurnId("turn-degraded-0");
        a.setDegraded(true);
        a.setStopReason("degraded");
        a.setSteps(List.of());
        when(agentOrchestrator.run(anyString(), anyString(), any())).thenReturn(a);
        when(currentUserResolver.resolveId(principal)).thenReturn(null);
        when(deepSeekChatService.chat(anyString(), anyString(), isNull(), isNull()))
                .thenReturn(legacyAnswer("配置引导"));

        Result<AgentChatResponse> r = controller.chat(req("随便问问"), httpRequest, principal);

        assertEquals(200, r.getCode().intValue());
        assertTrue(r.getData().getDegraded());
        assertTrue(r.getData().getSteps().isEmpty());
    }

    // ==================== 开关关闭：可回退 ====================

    @Test
    @DisplayName("agent.enabled=false：完全不走编排器，走旧链路且不报错")
    void disabledAgentFallsBackToLegacyChain() {
        props.setEnabled(false);
        when(currentUserResolver.resolveId(principal)).thenReturn(null);
        when(httpRequest.getHeader(anyString())).thenReturn(null);
        when(httpRequest.getRemoteAddr()).thenReturn("127.0.0.1");
        when(deepSeekChatService.chat(anyString(), anyString(), anyString(), isNull()))
                .thenReturn(legacyAnswer("旧链路答案"));

        Result<AgentChatResponse> r = controller.chat(req("宿舍有空调吗"), httpRequest, principal);

        assertEquals(200, r.getCode().intValue());
        assertEquals("旧链路答案", r.getData().getAnswer());
        assertTrue(r.getData().getDegraded(), "回退路径应标注 degraded，便于前端区分");
        assertTrue(r.getData().getSteps().isEmpty());
        verifyNoInteractions(agentOrchestrator);   // 关闭即彻底不触碰 Agent
    }

    // ==================== 入参校验 ====================

    @Test
    @DisplayName("空问题 / 超长问题：返回 error，不进入任何链路")
    void invalidQuestionRejectedEarly() {
        assertEquals(500, controller.chat(null, httpRequest, principal).getCode().intValue());
        ChatRequest blank = req("   ");
        assertEquals(500, controller.chat(blank, httpRequest, principal).getCode().intValue());
        ChatRequest longQ = req("啊".repeat(501));
        assertEquals(500, controller.chat(longQ, httpRequest, principal).getCode().intValue());

        verifyNoInteractions(agentOrchestrator);
        verify(deepSeekChatService, never()).chat(anyString(), anyString(), any(), isNull());
    }

    @Test
    @DisplayName("隔离：历史接口必须把当前 user_id 传给服务层（不能只凭 sessionId 查）")
    void historyPassesResolvedUserId() {
        when(currentUserResolver.resolveId(principal)).thenReturn(88L);
        when(deepSeekChatService.getHistory("sess-1", 88L, 50)).thenReturn(List.of());

        Result<java.util.Map<String, Object>> r = controller.history("sess-1", 50, principal);

        assertEquals(200, r.getCode().intValue());
        verify(deepSeekChatService).getHistory("sess-1", 88L, 50);
    }

    @Test
    @DisplayName("隔离：解析不出用户时标记 unauthenticated（服务层据此返回空）")
    void historyMarksUnauthenticatedWhenUserUnresolved() {
        when(currentUserResolver.resolveId(principal)).thenReturn(null);
        when(deepSeekChatService.getHistory("sess-1", null, 50)).thenReturn(List.of());

        Result<java.util.Map<String, Object>> r = controller.history("sess-1", 50, principal);

        assertEquals("unauthenticated", r.getData().get("reason"));
        assertEquals(List.of(), r.getData().get("messages"));
    }

    @Test
    @DisplayName("隔离：agent.enabled=false 的回退链路也要落真实 user_id")
    void disabledAgentFallbackPersistsUserId() {
        props.setEnabled(false);
        when(currentUserResolver.resolveId(principal)).thenReturn(66L);
        when(httpRequest.getHeader(anyString())).thenReturn(null);
        when(httpRequest.getRemoteAddr()).thenReturn("127.0.0.1");
        when(deepSeekChatService.chat(anyString(), anyString(), anyString(), eq(66L)))
                .thenReturn(legacyAnswer("回退答案"));

        controller.chat(req("宿舍有空调吗"), httpRequest, principal);

        verify(deepSeekChatService).chat(anyString(), anyString(), anyString(), eq(66L));
    }

    @Test
    @DisplayName("未配 key（isConfigured=false）：状态接口如实上报，不抛异常")
    void statusReportsNotConfigured() {
        when(deepSeekChatService.isConfigured()).thenReturn(false);
        when(toolRegistry.names()).thenReturn(List.of("search_knowledge", "calculate"));
        when(toolRegistry.size()).thenReturn(2);

        Result<java.util.Map<String, Object>> r = controller.status();

        assertEquals(200, r.getCode().intValue());
        assertEquals(false, r.getData().get("configured"));
        assertNotNull(r.getData().get("tip"));
    }

    @Test
    @DisplayName("状态接口如实上报当前模式与实际注册的工具名（取自注册表，非写死）")
    void statusReportsModeAndRegisteredTools() {
        when(deepSeekChatService.isConfigured()).thenReturn(true);
        when(toolRegistry.names()).thenReturn(List.of("plan_route", "search_knowledge"));
        when(toolRegistry.size()).thenReturn(2);

        Result<java.util.Map<String, Object>> on = controller.status();
        assertEquals(true, on.getData().get("agentEnabled"));
        assertEquals("agent", on.getData().get("mode"));
        assertEquals(2, ((Number) on.getData().get("toolCount")).intValue());
        assertEquals(List.of("plan_route", "search_knowledge"), on.getData().get("tools"));

        // 关闭开关后应如实变为 legacy，而不是继续宣称 Agent
        props.setEnabled(false);
        Result<java.util.Map<String, Object>> off = controller.status();
        assertEquals(false, off.getData().get("agentEnabled"));
        assertEquals("legacy", off.getData().get("mode"));
    }
}
