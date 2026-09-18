package com.freshman.agent;

import com.freshman.agent.dto.AgentAnswer;
import com.freshman.agent.llm.DeepSeekClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AgentOrchestrator 单元测试 —— **脚本化重放**，不消耗任何 API 额度。
 *
 * 覆盖规格 §3.4 的八个边界情况，其中最关键的是
 * `parallelToolCallsAreAlignedAndOrdered`：一次返回多个 tool_calls 时，
 * 回填的 `role:tool` 消息的 `tool_call_id` 必须与 assistant 消息里的 id
 * **精确一致且顺序保持**，否则下一轮 API 直接返回 400。
 */
class AgentOrchestratorTest {

    private ScriptedClient client;
    private AgentProperties props;
    private AgentOrchestrator orchestrator;
    private RecordingTool echoTool;
    private com.freshman.mapper.AgentToolCallLogMapper traceMapper;
    private final AtomicInteger executions = new AtomicInteger();

    /** 记录每次执行入参的假工具 */
    class RecordingTool implements AgentTool {
        final String name;
        final boolean throwOnExecute;

        RecordingTool(String name, boolean throwOnExecute) {
            this.name = name;
            this.throwOnExecute = throwOnExecute;
        }

        @Override public String name() { return name; }
        @Override public String description() { return "测试工具"; }
        @Override public Map<String, Object> parameters() {
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "object");
            schema.put("properties", Map.of("query", Map.of("type", "string")));
            schema.put("required", List.of("query"));
            return schema;
        }
        @Override public ToolResult execute(Map<String, Object> args, ToolContext ctx) {
            executions.incrementAndGet();
            if (throwOnExecute) {
                throw new IllegalStateException("工具内部炸了");
            }
            return ToolResult.ok("工具结果：" + args.get("query"));
        }
    }

    /** 按脚本返回响应的假客户端；记录收到的 messages 以便断言 |
     */
    static class ScriptedClient implements DeepSeekClient {
        final Deque<ChatTurn> script = new ArrayDeque<>();
        final List<List<Map<String, Object>>> received = new ArrayList<>();
        boolean configured = true;

        @Override public boolean isConfigured() { return configured; }

        @Override public ChatTurn chat(List<Map<String, Object>> messages, String toolsJson) {
            received.add(new ArrayList<>(messages));
            if (script.isEmpty()) {
                return ChatTurn.answer("兜底答案", 10);
            }
            return script.poll();
        }
    }

    @BeforeEach
    void setUp() {
        client = new ScriptedClient();
        props = new AgentProperties();
        props.setEnabled(true);
        props.setMaxSteps(5);
        props.setTokenBudget(30000);
        props.setToolResultMaxChars(4000);
        echoTool = new RecordingTool("search_knowledge", false);
        traceMapper = org.mockito.Mockito.mock(com.freshman.mapper.AgentToolCallLogMapper.class);
        orchestrator = new AgentOrchestrator(client, new ToolRegistry(List.of(echoTool)), props, traceMapper);
        executions.set(0);
    }

    private static DeepSeekClient.ChatTurn.ToolCall call(String id, String name, String args) {
        return new DeepSeekClient.ChatTurn.ToolCall(id, name, args);
    }

    // ==================== 1. 基本路径 ====================

    @Test
    void singleToolThenFinalAnswer() {
        client.script.add(DeepSeekClient.ChatTurn.tools(
                List.of(call("c1", "search_knowledge", "{\"query\":\"宿舍空调\"}")), 100));
        client.script.add(DeepSeekClient.ChatTurn.answer("厚德学区有空调 [1]", 50));

        AgentAnswer a = orchestrator.run("宿舍有空调吗", "s1", List.of());

        assertEquals("final_answer", a.getStopReason());
        assertEquals("厚德学区有空调 [1]", a.getAnswer());
        assertEquals(1, a.getSteps().size());
        assertEquals("success", a.getSteps().get(0).getStatus());
        assertEquals("search_knowledge", a.getSteps().get(0).getToolName());
        assertEquals(150, a.getTotalTokens());
        assertTrue(a.getTotalMs() >= 0);
        assertEquals(1, executions.get());
    }

    // ==================== 4. 并行 tool_calls 的 id 对齐（最关键） ====================

    @Test
    void parallelToolCallsAreAlignedAndOrdered() {
        client.script.add(DeepSeekClient.ChatTurn.tools(List.of(
                call("call_a", "search_knowledge", "{\"query\":\"甲\"}"),
                call("call_b", "search_knowledge", "{\"query\":\"乙\"}")), 100));
        client.script.add(DeepSeekClient.ChatTurn.answer("综合答案", 50));

        AgentAnswer a = orchestrator.run("两个问题", "s2", List.of());

        assertEquals(2, a.getSteps().size(), "两个 tool_calls 都应被执行");
        assertEquals(2, executions.get());

        // 第二轮发给模型的消息里：assistant(tool_calls) 之后紧跟两条 tool 消息，id 一一对应且顺序保持
        List<Map<String, Object>> second = client.received.get(1);
        int assistantIdx = -1;
        for (int i = 0; i < second.size(); i++) {
            if ("assistant".equals(second.get(i).get("role")) && second.get(i).containsKey("tool_calls")) {
                assistantIdx = i;
                break;
            }
        }
        assertTrue(assistantIdx >= 0, "必须回填 assistant 的 tool_calls 消息");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> toolCalls =
                (List<Map<String, Object>>) second.get(assistantIdx).get("tool_calls");
        assertEquals(2, toolCalls.size());
        assertEquals("call_a", toolCalls.get(0).get("id"));
        assertEquals("call_b", toolCalls.get(1).get("id"));

        Map<String, Object> toolMsg1 = second.get(assistantIdx + 1);
        Map<String, Object> toolMsg2 = second.get(assistantIdx + 2);
        assertEquals("tool", toolMsg1.get("role"));
        assertEquals("tool", toolMsg2.get("role"));
        assertEquals("call_a", toolMsg1.get("tool_call_id"),
                "tool 消息的 id 必须与 assistant 消息里的 id 精确一致，否则下一轮 API 返回 400");
        assertEquals("call_b", toolMsg2.get("tool_call_id"));
    }

    // ==================== 1/2/3. 三种错误回填 ====================

    @Test
    void hallucinatedToolNameSelfHealsWithoutThrowing() {
        client.script.add(DeepSeekClient.ChatTurn.tools(
                List.of(call("c1", "no_such_tool", "{}")), 100));
        client.script.add(DeepSeekClient.ChatTurn.answer("换个方式回答", 50));

        AgentAnswer a = assertDoesNotThrow(() -> orchestrator.run("问题", "s3", List.of()));

        assertEquals(1, a.getSteps().size());
        assertEquals("unknown_tool", a.getSteps().get(0).getStatus());
        assertEquals("final_answer", a.getStopReason(), "幻觉工具名不应终止循环");
        assertEquals(0, executions.get(), "不存在的工具当然不会被真的执行");
    }

    @Test
    void malformedArgumentsJsonIsBackfilled() {
        client.script.add(DeepSeekClient.ChatTurn.tools(
                List.of(call("c1", "search_knowledge", "{\"query\": 截断的")), 100));
        client.script.add(DeepSeekClient.ChatTurn.answer("重试后的答案", 50));

        AgentAnswer a = orchestrator.run("问题", "s4", List.of());

        assertEquals("invalid_arguments", a.getSteps().get(0).getStatus());
        assertEquals(0, executions.get(), "参数解析失败时不得执行工具");
        assertEquals("final_answer", a.getStopReason());
    }

    @Test
    void missingRequiredArgumentIsBackfilled() {
        client.script.add(DeepSeekClient.ChatTurn.tools(
                List.of(call("c1", "search_knowledge", "{}")), 100));
        client.script.add(DeepSeekClient.ChatTurn.answer("答案", 50));

        AgentAnswer a = orchestrator.run("问题", "s5", List.of());

        assertEquals("invalid_arguments", a.getSteps().get(0).getStatus());
        assertTrue(a.getSteps().get(0).getError().contains("query"), "应指出缺哪个参数");
        assertEquals(0, executions.get());
    }

    @Test
    void toolInternalExceptionDoesNotBreakTheLoop() {
        orchestrator = new AgentOrchestrator(client,
                new ToolRegistry(List.of(new RecordingTool("search_knowledge", true))), props, traceMapper);
        client.script.add(DeepSeekClient.ChatTurn.tools(
                List.of(call("c1", "search_knowledge", "{\"query\":\"甲\"}")), 100));
        client.script.add(DeepSeekClient.ChatTurn.answer("降级后的答案", 50));

        AgentAnswer a = assertDoesNotThrow(() -> orchestrator.run("问题", "s6", List.of()));

        assertEquals("error", a.getSteps().get(0).getStatus());
        assertEquals("final_answer", a.getStopReason(), "工具异常不应中断循环");
    }

    // ==================== 5. 结果截断 ====================

    @Test
    void oversizedToolResultIsTruncatedBeforeBackfill() {
        props.setToolResultMaxChars(50);
        orchestrator = new AgentOrchestrator(client, new ToolRegistry(List.of(new BigResultTool())), props, traceMapper);
        client.script.add(DeepSeekClient.ChatTurn.tools(
                List.of(call("c1", "big_tool", "{}")), 100));
        client.script.add(DeepSeekClient.ChatTurn.answer("答案", 50));

        AgentAnswer a = orchestrator.run("问题", "s7", List.of());

        List<Map<String, Object>> second = client.received.get(1);
        Map<String, Object> toolMsg = second.stream()
                .filter(m -> "tool".equals(m.get("role"))).findFirst().orElseThrow();
        String content = String.valueOf(toolMsg.get("content"));
        assertTrue(content.length() <= 50 + 60, "回填内容应被截断，实际 " + content.length());
        assertTrue(content.contains("已截断"), "应注明已截断：" + content);
    }

    /** 返回超长结果的工具 */
    static class BigResultTool implements AgentTool {
        @Override public String name() { return "big_tool"; }
        @Override public String description() { return "返回超长结果的测试工具"; }
        @Override public Map<String, Object> parameters() {
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "object");
            schema.put("properties", Map.of());
            return schema;
        }
        @Override public ToolResult execute(Map<String, Object> args, ToolContext ctx) {
            return ToolResult.ok("长".repeat(2000));
        }
    }

    // ==================== 7. 步数上限 ====================

    @Test
    void maxStepsStopsLoopWithFallbackAnswer() {
        props.setMaxSteps(2);
        orchestrator = new AgentOrchestrator(client, new ToolRegistry(List.of(echoTool)), props, traceMapper);
        // 脚本里全是 tool_calls，永远不出最终答案
        for (int i = 0; i < 5; i++) {
            client.script.add(DeepSeekClient.ChatTurn.tools(
                    List.of(call("c" + i, "search_knowledge", "{\"query\":\"q" + i + "\"}")), 10));
        }

        AgentAnswer a = orchestrator.run("问题", "s8", List.of());

        assertEquals("max_steps", a.getStopReason());
        assertEquals(2, a.getSteps().size(), "恰好执行 maxSteps 步");
        assertNotNull(a.getAnswer(), "超步数要返回中间结果摘要，而不是空答案或异常");
        assertTrue(a.getAnswer().contains("未能得出完整结论"), "答案应说明情况：" + a.getAnswer());
    }

    // ==================== 8. token 预算 ====================

    @Test
    void tokenBudgetStopsLoop() {
        props.setTokenBudget(150);
        orchestrator = new AgentOrchestrator(client, new ToolRegistry(List.of(echoTool)), props, traceMapper);
        client.script.add(DeepSeekClient.ChatTurn.tools(
                List.of(call("c1", "search_knowledge", "{\"query\":\"甲\"}")), 200));   // 一次就超预算
        client.script.add(DeepSeekClient.ChatTurn.answer("不该走到的答案", 10));

        AgentAnswer a = orchestrator.run("问题", "s9", List.of());

        assertEquals("budget_exhausted", a.getStopReason());
        assertNotNull(a.getAnswer(), "超预算也要给出中间结果");
        assertEquals(1, client.received.size(), "超预算后不应再发起下一轮调用");
    }

    // ==================== 轨迹落库 ====================

    @Test
    void tracesArePersistedWithTurnIdStatusAndTiming() {
        client.script.add(DeepSeekClient.ChatTurn.tools(
                List.of(call("c1", "search_knowledge", "{\"query\":\"宿舍\"}"),
                        call("c2", "no_such_tool", "{}")), 100));
        client.script.add(DeepSeekClient.ChatTurn.answer("答案", 50));

        AgentAnswer a = orchestrator.run("问题", "s-trace", List.of());

        org.mockito.ArgumentCaptor<com.freshman.entity.AgentToolCallLog> captor =
                org.mockito.ArgumentCaptor.forClass(com.freshman.entity.AgentToolCallLog.class);
        org.mockito.Mockito.verify(traceMapper, org.mockito.Mockito.times(2)).insert(captor.capture());

        var logs = captor.getAllValues();
        assertEquals(2, logs.size());
        for (var l : logs) {
            assertEquals(a.getTurnId(), l.getTurnId(), "同一次提问的多步必须共享同一个 turnId");
            assertEquals("s-trace", l.getSessionId());
            assertNotNull(l.getDurationMs());
            assertNotNull(l.getStatus());
        }
        assertEquals("success", logs.get(0).getStatus());
        assertEquals("unknown_tool", logs.get(1).getStatus(), "失败步骤也要落库（便于事后分析）");
        assertEquals("search_knowledge", logs.get(0).getToolName());
    }

    @Test
    void traceFailureDoesNotBreakTheAnswer() {
        org.mockito.Mockito.doThrow(new RuntimeException("模拟表不存在"))
                .when(traceMapper).insert(org.mockito.ArgumentMatchers.any());
        client.script.add(DeepSeekClient.ChatTurn.tools(
                List.of(call("c1", "search_knowledge", "{\"query\":\"甲\"}")), 100));
        client.script.add(DeepSeekClient.ChatTurn.answer("答案依然返回", 50));

        AgentAnswer a = assertDoesNotThrow(() -> orchestrator.run("问题", "s-trace2", List.of()));

        assertEquals("答案依然返回", a.getAnswer(), "轨迹落库失败绝不能影响回答");
        assertEquals("final_answer", a.getStopReason());
    }

    // ==================== 降级 ====================

    @Test
    void notConfiguredDegradesWithoutCallingClient() {
        client.configured = false;

        AgentAnswer a = orchestrator.run("问题", "s10", List.of());

        assertTrue(a.isDegraded());
        assertEquals("degraded", a.getStopReason());
        assertTrue(client.received.isEmpty(), "未配置时不应发起任何调用");
    }

    @Test
    void disabledAgentDegradesImmediately() {
        props.setEnabled(false);
        AgentAnswer a = orchestrator.run("问题", "s11", List.of());
        assertTrue(a.isDegraded());
        assertTrue(client.received.isEmpty());
    }

    @Test
    void firstCallFailureDegrades() {
        client.script.add(DeepSeekClient.ChatTurn.fail("账户余额不足"));

        AgentAnswer a = orchestrator.run("问题", "s12", List.of());

        assertTrue(a.isDegraded());
        assertEquals("degraded", a.getStopReason());
        assertTrue(client.received.size() <= 2, "首轮失败即降级，不应继续重试循环");
    }
}
