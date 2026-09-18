package com.freshman.it;

import com.freshman.agent.AgentOrchestrator;
import com.freshman.agent.dto.AgentAnswer;
import com.freshman.agent.dto.AgentStep;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Agent 的**真实端到端验证**（真实 MySQL + 真实 DeepSeek API）。
 *
 * 为什么这个测试很关键：`OpenAiCompatibleDeepSeekClient` 是一段从未被真实调用过的代码 ——
 * `tools` 数组格式、`tool_calls` 解析、`arguments` 作为 JSON 字符串的处理、
 * 以及**回填时 `tool_call_id` 的对齐**，任何一处写错都会让真实 API 直接返回 400。
 * 单元测试用脚本化客户端覆盖了循环逻辑，但**验证不了协议实现本身**。
 *
 * 运行：
 *   $env:DEEPSEEK_API_KEY = "<key>"
 *   mvn test -Dtest=AgentEvalIT -Dtest.excludedGroups=   （注意放开 eval 层）
 *
 * 成本：每个用例 1~3 次对话调用，全类在**分币级**。
 */
@Tag("it")
@Tag("eval")
@SpringBootTest
class AgentEvalIT {

    @Autowired
    private AgentOrchestrator orchestrator;

    @Autowired
    private Environment env;

    @BeforeEach
    void requireDeepSeekKey() {
        String key = env.getProperty("app.ai.deepseek.apiKey", "");
        Assumptions.assumeTrue(key != null && !key.isBlank(),
                "未设置 DEEPSEEK_API_KEY，跳过需要真实 DeepSeek API 的 Agent 端到端验证");
        Assumptions.assumeTrue(env.getProperty("app.ai.deepseek.agent.enabled", Boolean.class, true),
                "Agent 开关未启用");
    }

    /** 典型可答问题：应触发 search_knowledge 并给出带材料的答案 */
    @Test
    void realRoundTripUsesToolsAndReturnsAnswer() {
        String session = "agent-it-" + UUID.randomUUID();

        AgentAnswer a = orchestrator.run("宿舍有空调吗", session, List.of());

        printTrace(a);

        assertFalse(a.isDegraded(), "已配置 key，不应降级。stopReason=" + a.getStopReason()
                + " answer=" + a.getAnswer());
        assertFalse(a.getSteps().isEmpty(),
                "问学校具体事实时模型应调用工具（若为空，说明 tools 数组未被模型接受或协议有误）");
        assertNotNull(a.getAnswer());
        assertFalse(a.getAnswer().isBlank(), "应给出最终答案");

        // 真实 API 能跑完多步循环，说明 tool_call_id 回填是对齐的
        // —— 对不齐时 DeepSeek 会在第二轮直接返回 400，表现为 degraded/error
        assertTrue("final_answer".equals(a.getStopReason()),
                "正常路径的 stopReason 应为 final_answer，实际=" + a.getStopReason());

        boolean usedKnowledge = a.getSteps().stream()
                .anyMatch(s -> "search_knowledge".equals(s.getToolName()));
        assertTrue(usedKnowledge, "应至少调用一次 search_knowledge，实际步骤：" + summarize(a));
    }

    /** 时间类问题：应走 get_current_time（证明模型会按描述选工具） */
    @Test
    void timeQuestionUsesCurrentTimeTool() {
        AgentAnswer a = orchestrator.run("今天是几号？", "agent-it-" + UUID.randomUUID(), List.of());

        printTrace(a);

        assertFalse(a.isDegraded(), "不应降级：" + a.getStopReason());
        assertNotNull(a.getAnswer());
        assertTrue(a.getSteps().stream().anyMatch(s -> "get_current_time".equals(s.getToolName())),
                "时间类问题应调用 get_current_time，实际：" + summarize(a));
    }

    private void printTrace(AgentAnswer a) {
        System.out.println("[AgentIT] stopReason=" + a.getStopReason()
                + " degraded=" + a.isDegraded()
                + " steps=" + a.getSteps().size()
                + " tokens=" + a.getTotalTokens()
                + " 耗时=" + a.getTotalMs() + "ms");
        for (AgentStep s : a.getSteps()) {
            System.out.println("[AgentIT]   step" + s.getStepNo() + " " + s.getToolName()
                    + "(" + s.getArguments() + ") -> " + s.getStatus() + " " + s.getDurationMs() + "ms");
        }
        System.out.println("[AgentIT] 答案=" + a.getAnswer());
    }

    private String summarize(AgentAnswer a) {
        return a.getSteps().stream().map(AgentStep::getToolName).toList().toString();
    }
}
