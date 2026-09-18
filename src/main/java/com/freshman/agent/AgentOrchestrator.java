package com.freshman.agent;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.freshman.agent.dto.AgentAnswer;
import com.freshman.agent.dto.AgentStep;
import com.freshman.agent.llm.DeepSeekClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Agent 编排器：自研 function-calling 循环
 *
 * ```
 * messages = [system] + [user]
 * for step in 1..maxSteps:
 *     turn = client.chat(messages, tools)
 *     if 无 tool_calls → 最终答案，stopReason=final_answer
 *     for call in turn.toolCalls:            # 支持一次返回多个（并行）
 *         执行（幻觉工具名 / 参数非法 → 回填错误而不是抛异常）
 *         messages += [assistant(tool_calls), tool(call_id, 结果)]
 *     超 token 预算 → stopReason=budget_exhausted
 * 超步数 → stopReason=max_steps（返回中间结果摘要，不是异常）
 * ```
 *
 * **八个边界情况全部处理**（规格 §3.4）：
 *  1. 幻觉工具名 → 回填 `unknown_tool`，循环继续（不抛异常）
 *  2. schema 违规 → 回填 `invalid_arguments`
 *  3. `arguments` 非法 JSON → 与 ② 同一回填路径
 *  4. 并行 tool_calls → 逐个执行、逐个回填，**`tool_call_id` 精确对齐且顺序保持**
 *  5. 工具结果过长 → 截断至 `toolResultMaxChars`
 *  6. `reasoning_content` → 客户端已不返回它，永不回填（污染后续轮次）
 *  7. 步数上限 → `max_steps`
 *  8. token 预算 → `budget_exhausted`
 *
 * 降级：客户端未配置或首轮调用失败 → `degraded=true` + `stopReason=degraded`，
 * 由控制层复用 `DeepSeekChatService` 的配置引导/无工具 chat（本类不依赖 service 层，
 * 以免形成 agent ↔ service 的环）。
 *
 * 所属模块：DeepSeek 问答 / Agent
 */
@Component
public class AgentOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(AgentOrchestrator.class);

    private static final String SYSTEM_PROMPT =
            "你是东北石油大学智慧迎新系统的智能助手，可以调用工具获取真实数据来回答新生的问题。\n\n"
                    + "工作原则：\n"
                    + "1. 需要学校的具体事实（流程、规定、费用、地点、设施等）时，**先调用工具**，不要凭记忆回答\n"
                    + "2. 工具返回的材料不足时，如实说明，不要编造\n"
                    + "3. 可以连续调用多个工具；拿到足够信息后给出简洁、友好的中文回答\n"
                    + "4. 回答涉及来源时，说明信息来自学校知识库或结构化数据";

    private final DeepSeekClient client;
    private final ToolRegistry registry;
    private final AgentProperties props;
    private final com.freshman.mapper.AgentToolCallLogMapper traceMapper;

    public AgentOrchestrator(DeepSeekClient client, ToolRegistry registry, AgentProperties props,
                             com.freshman.mapper.AgentToolCallLogMapper traceMapper) {
        this.client = client;
        this.registry = registry;
        this.props = props;
        this.traceMapper = traceMapper;
    }

    /**
     * 跑一轮 Agent。
     *
     * @param question  用户问题
     * @param sessionId 会话标识（用于工具上下文）
     * @param history   历史消息（可为空列表）
     */
    public AgentAnswer run(String question, String sessionId, List<Map<String, Object>> history) {
        long start = System.currentTimeMillis();
        AgentAnswer answer = new AgentAnswer();
        String turnId = java.util.UUID.randomUUID().toString();
        answer.setTurnId(turnId);

        if (!props.isEnabled() || !client.isConfigured()) {
            answer.setDegraded(true);
            answer.setStopReason("degraded");
            answer.setTotalMs(System.currentTimeMillis() - start);
            log.info("[Agent] 未启用或未配置，走降级路径");
            return answer;
        }

        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", SYSTEM_PROMPT));
        if (history != null) {
            messages.addAll(history);
        }
        messages.add(Map.of("role", "user", "content", question));

        String toolsJson = registry.toolsJson();
        ToolContext ctx = new ToolContext(sessionId, null, null);
        int tokens = 0;
        boolean finalAnswerReached = false;

        for (int step = 1; step <= props.getMaxSteps(); step++) {
            DeepSeekClient.ChatTurn turn = client.chat(messages, toolsJson);

            if (turn.failed()) {
                log.warn("[Agent] 第 {} 步调用失败：{}", step, turn.error());
                answer.setDegraded(true);
                answer.setStopReason(step == 1 ? "degraded" : "error");
                break;
            }
            tokens += turn.totalTokens();

            // ---- 无 tool_calls → 最终答案 ----
            if (!turn.hasToolCalls()) {
                answer.setAnswer(turn.content());
                answer.setStopReason("final_answer");
                finalAnswerReached = true;
                break;
            }

            // ---- 把 assistant 的 tool_calls 原样回填（id 必须完全一致，否则下一轮 400）----
            messages.add(assistantToolCallsMessage(turn.toolCalls()));

            // ---- 逐个执行并逐个回填（支持并行 tool_calls）----
            for (DeepSeekClient.ChatTurn.ToolCall call : turn.toolCalls()) {
                AgentStep s = executeTool(call, step, ctx);
                answer.getSteps().add(s);
                persistTrace(turnId, sessionId, s);
                messages.add(toolMessage(call.id(), s.getBackfillContent()));
            }

            // ---- 边界 8：token 预算 ----
            if (tokens > props.getTokenBudget()) {
                answer.setStopReason("budget_exhausted");
                log.warn("[Agent] token 预算耗尽（{} > {}），停止循环", tokens, props.getTokenBudget());
                break;
            }
        }

        if (!finalAnswerReached && answer.getAnswer() == null && !answer.isDegraded()) {
            if ("final_answer".equals(answer.getStopReason())) {
                // 循环自然走完 maxSteps 仍未出最终答案
                answer.setStopReason("max_steps");
            }
            answer.setAnswer(buildFallbackAnswer(answer));
        }

        answer.setTotalTokens(tokens);
        answer.setTotalMs(System.currentTimeMillis() - start);
        log.info("[Agent] 完成：stopReason={}, steps={}, tokens={}, 耗时={}ms",
                answer.getStopReason(), answer.getSteps().size(), tokens, answer.getTotalMs());
        return answer;
    }

    // ==================== 轨迹落库 ====================

    /** 落库单步轨迹；**观测失败绝不影响回答**（与检索日志同样的容错策略） */
    private void persistTrace(String turnId, String sessionId, AgentStep s) {
        if (!props.isTraceEnabled() || traceMapper == null) {
            return;
        }
        try {
            com.freshman.entity.AgentToolCallLog log = new com.freshman.entity.AgentToolCallLog();
            log.setTurnId(turnId);
            log.setSessionId(sessionId);
            log.setStepNo(s.getStepNo());
            log.setToolName(truncate(s.getToolName(), 50));
            log.setArguments(truncate(s.getArguments(), 1000));
            log.setResultDigest(truncate(s.getResultDigest(), 1000));
            log.setDurationMs(s.getDurationMs());
            log.setStatus(truncate(s.getStatus(), 20));
            log.setError(truncate(s.getError(), 500));
            traceMapper.insert(log);
        } catch (Exception e) {
            log.warn("[Agent] 轨迹落库失败（不影响回答）：{}", e.getMessage());
        }
    }

    // ==================== 工具执行（边界 1/2/3/5） ====================

    private AgentStep executeTool(DeepSeekClient.ChatTurn.ToolCall call, int stepNo, ToolContext ctx) {
        AgentStep s = new AgentStep();
        s.setStepNo(stepNo);
        s.setToolName(call.name());
        s.setArguments(call.argumentsJson());
        long t0 = System.currentTimeMillis();

        Optional<AgentTool> tool = registry.get(call.name());
        if (tool.isEmpty()) {
            // 边界 1：幻觉工具名 —— 不抛异常，把可用工具告诉模型让它自愈
            s.setStatus("unknown_tool");
            s.setError("工具不存在");
            s.setBackfillContent("{\"error\":\"unknown_tool\",\"available\":" + registry.names() + "}");
            s.setResultDigest("未注册的工具：" + call.name());
            s.setDurationMs((int) (System.currentTimeMillis() - t0));
            log.warn("[Agent] 模型调用了不存在的工具：{}（已回填可用列表）", call.name());
            return s;
        }

        Map<String, Object> args = parseArgs(call.argumentsJson());
        if (args == null) {
            // 边界 3：arguments 是 JSON 字符串，模型可能输出截断/多余文字
            s.setStatus("invalid_arguments");
            s.setError("参数不是合法 JSON");
            s.setBackfillContent("{\"error\":\"invalid_arguments\",\"detail\":\"arguments 不是合法 JSON 对象\"}");
            s.setResultDigest("参数解析失败");
            s.setDurationMs((int) (System.currentTimeMillis() - t0));
            log.warn("[Agent] 工具 {} 的参数不是合法 JSON：{}", call.name(), call.argumentsJson());
            return s;
        }

        // 边界 2：必填项校验（schema 的 required）
        String missing = firstMissingRequired(tool.get(), args);
        if (missing != null) {
            s.setStatus("invalid_arguments");
            s.setError("缺少必填参数 " + missing);
            s.setBackfillContent("{\"error\":\"invalid_arguments\",\"detail\":\"缺少必填参数 " + missing + "\"}");
            s.setResultDigest("缺少必填参数");
            s.setDurationMs((int) (System.currentTimeMillis() - t0));
            return s;
        }

        try {
            ToolResult r = tool.get().execute(args, ctx);
            String content = r.content() == null ? "" : r.content();
            // 边界 5：结果过长 → 截断（防 token 爆炸）
            if (content.length() > props.getToolResultMaxChars()) {
                content = content.substring(0, props.getToolResultMaxChars())
                        + "\n…[已截断，完整结果 " + r.content().length() + " 字]";
            }
            s.setStatus(r.success() ? "success" : "error");
            s.setBackfillContent(content);
            s.setResultDigest(truncate(content, 1000));
            s.setMeta(r.meta());
            if (!r.success()) {
                s.setError(truncate(content, 200));
            }
        } catch (Exception e) {
            // 工具内部异常也不能中断循环
            log.warn("[Agent] 工具 {} 执行异常：{}", call.name(), e.getMessage());
            s.setStatus("error");
            s.setError(e.getClass().getSimpleName());
            s.setBackfillContent("{\"error\":\"tool_failed\",\"detail\":\"" + e.getClass().getSimpleName() + "\"}");
            s.setResultDigest("执行异常");
        }
        s.setDurationMs((int) (System.currentTimeMillis() - t0));
        return s;
    }

    /** 解析 arguments JSON 字符串；非法或非对象返回 null */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseArgs(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            JSONObject obj = JSONUtil.parseObj(argumentsJson);
            return new LinkedHashMap<>(obj);
        } catch (Exception e) {
            return null;
        }
    }

    /** 返回第一个缺失的必填参数名；无缺失返回 null */
    @SuppressWarnings("unchecked")
    private static String firstMissingRequired(AgentTool tool, Map<String, Object> args) {
        Object required = tool.parameters().get("required");
        if (!(required instanceof List<?> list)) {
            return null;
        }
        for (Object o : list) {
            String key = String.valueOf(o);
            if (!args.containsKey(key) || args.get(key) == null) {
                return key;
            }
        }
        return null;
    }

    // ==================== 消息组装（边界 4/6） ====================

    /** assistant 消息携带 tool_calls：id/name/arguments 必须与模型返回的原样一致 */
    private static Map<String, Object> assistantToolCallsMessage(List<DeepSeekClient.ChatTurn.ToolCall> calls) {
        List<Map<String, Object>> arr = new ArrayList<>(calls.size());
        for (DeepSeekClient.ChatTurn.ToolCall c : calls) {
            Map<String, Object> fn = new LinkedHashMap<>();
            fn.put("name", c.name());
            fn.put("arguments", c.argumentsJson() == null ? "{}" : c.argumentsJson());
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("id", c.id());
            one.put("type", "function");
            one.put("function", fn);
            arr.add(one);
        }
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("role", "assistant");
        msg.put("content", "");
        msg.put("tool_calls", arr);   // 注意：不回填 reasoning_content
        return msg;
    }

    /** tool 消息：tool_call_id 必须与对应 tool_call 的 id 精确一致 */
    private static Map<String, Object> toolMessage(String toolCallId, String content) {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("role", "tool");
        msg.put("tool_call_id", toolCallId);
        msg.put("content", content == null ? "" : content);
        return msg;
    }

    /** 循环被迫停止（超步数/超预算）时的兜底答案：汇总已获得的工具结果 */
    private String buildFallbackAnswer(AgentAnswer answer) {
        StringBuilder sb = new StringBuilder("（已尝试 ").append(answer.getSteps().size())
                .append(" 次工具调用，未能得出完整结论，以下是已获取的信息）\n\n");
        int shown = 0;
        for (int i = answer.getSteps().size() - 1; i >= 0 && shown < 3; i--, shown++) {
            AgentStep s = answer.getSteps().get(i);
            if ("success".equals(s.getStatus()) && s.getResultDigest() != null) {
                sb.append("· ").append(s.getResultDigest()).append("\n");
            }
        }
        sb.append("\n建议换个更具体的问法，或直接咨询辅导员。");
        return sb.toString();
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
