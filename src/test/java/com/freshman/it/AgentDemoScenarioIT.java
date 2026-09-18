package com.freshman.it;

import com.freshman.agent.AgentOrchestrator;
import com.freshman.agent.dto.AgentAnswer;
import com.freshman.agent.dto.AgentStep;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 规格 §7 的 5 个演示场景，端到端真实运行（真实 MySQL + 真实 DeepSeek API）。
 *
 * 与 {@link AgentEvalIT} 的分工：那边验证**协议正确性**（tools 数组、tool_calls 解析、
 * tool_call_id 回填）；这里验证**整句自然语言下的选路能力** —— 模型面对一段包含多个诉求的
 * 复合问题，能否自主决定"先查数据、再算路线"，而不是只答一半。
 *
 * 运行（只需 DeepSeek key；`plan_route` 拿真实路线还需百度 token，缺 token 不影响"是否调了该工具"的断言）：
 * <pre>
 *   $env:DEEPSEEK_API_KEY = "&lt;key&gt;"
 *   mvn test -Dtest=AgentDemoScenarioIT -Dtest.excludedGroups=
 * </pre>
 *
 * 断言用**「期望工具是实际调用集合的子集」**而非精确序列：模型在满足诉求的前提下多调一个工具
 * 属于合理行为，卡死顺序会把正确行为判成失败（脆弱断言）。同时把完整轨迹打印出来，
 * 便于人工核对"是否真的按需调用"。
 */
@Tag("it")
@Tag("eval")
@SpringBootTest
class AgentDemoScenarioIT {

    @Autowired
    private AgentOrchestrator orchestrator;

    @Autowired
    private Environment env;

    @BeforeEach
    void requireDeepSeekKey() {
        String key = env.getProperty("app.ai.deepseek.apiKey", "");
        Assumptions.assumeTrue(key != null && !key.isBlank(),
                "未设置 DEEPSEEK_API_KEY，跳过演示场景（这是唯一需要你提供 key 的步骤）");
        Assumptions.assumeTrue(env.getProperty("app.ai.deepseek.agent.enabled", Boolean.class, true),
                "Agent 开关未启用");
    }

    /** 场景 1：宿舍价格/空调 + 步行距离 —— 复合问题应既查校园数据又算路线 */
    @Test
    @DisplayName("场景1：宿舍多少钱/有空调吗/走到第一食堂多远 → query_campus_data + plan_route")
    void scenario1DormFactsAndRoute() {
        assertScenario(
                "厚德学区宿舍多少钱一年？有空调吗？从那儿走到第一食堂多远？",
                List.of("query_campus_data", "plan_route"));
    }

    /** 场景 2：报到材料 + 步行路线 —— 知识库检索 + 路线规划 */
    @Test
    @DisplayName("场景2：报到带什么材料/正门怎么走到图书馆 → search_knowledge + plan_route")
    void scenario2RegistrationMaterialsAndRoute() {
        assertScenario(
                "报到要带什么材料？从学校正门怎么走到图书馆？",
                List.of("search_knowledge", "plan_route"));
    }

    /** 场景 3：单跳知识检索 */
    @Test
    @DisplayName("场景3：军训服不合身怎么办 → search_knowledge（单步）")
    void scenario3SingleKnowledgeLookup() {
        assertScenario("军训服不合身怎么办？", List.of("search_knowledge"));
    }

    /**
     * 场景 4：知识库未收录的主观问题 —— 关键在**如实说明**而不是编造。
     * 这里只断言"调了检索且最终给出答案、且未降级"；答案是否认怂由人工看打印的文本。
     */
    @Test
    @DisplayName("场景4：食堂好吃吗（知识库无此主观结论）→ search_knowledge 后如实说明，不编造")
    void scenario4HonestWhenKnowledgeMisses() {
        AgentAnswer a = run("你们学校食堂好吃吗？");
        printTrace(a);

        assertFalse(a.isDegraded(), "不应降级：" + a.getStopReason());
        assertTrue(calledTools(a).contains("search_knowledge"),
                "食品相关问题应先检索知识库，实际：" + calledTools(a));
        assertNotNull(a.getAnswer());
        assertFalse(a.getAnswer().isBlank(), "即使没查到也应给出说明性回答");
        assertEquals("final_answer", a.getStopReason());
    }

    /** 场景 5：时间 + 计算 —— 两个非检索类工具的组合 */
    @Test
    @DisplayName("场景5：今天几号/军训14天结束是哪天 → get_current_time + calculate")
    void scenario5TimeAndCalculation() {
        assertScenario("今天几号？军训 14 天的话结束是哪天？",
                List.of("get_current_time", "calculate"));
    }

    // ==================== 共用断言 ====================

    private void assertScenario(String question, List<String> expectedTools) {
        AgentAnswer a = run(question);
        printTrace(a);

        assertFalse(a.isDegraded(), "不应降级。stopReason=" + a.getStopReason()
                + " answer=" + a.getAnswer());
        assertNotNull(a.getAnswer(), "应有最终答案");
        assertFalse(a.getAnswer().isBlank(), "答案不应为空");
        assertEquals("final_answer", a.getStopReason(),
                "正常路径应停在 final_answer（若为 max_steps，说明循环没收敛）");

        List<String> called = calledTools(a);
        assertFalse(called.isEmpty(), "复合问题应触发工具调用，实际没有调用任何工具 —— "
                + "通常意味着 tools 数组没被模型接受");
        for (String tool : expectedTools) {
            assertTrue(called.contains(tool),
                    "期望调用 " + tool + "，实际调用：" + called
                            + "（若缺 plan_route 且回答里称无法规划，先确认 BAIDU_MAP_AUTH_TOKEN 是否已设置）");
        }
    }

    private AgentAnswer run(String question) {
        return orchestrator.run(question, "demo-" + UUID.randomUUID(), List.of());
    }

    private List<String> calledTools(AgentAnswer a) {
        return a.getSteps().stream().map(AgentStep::getToolName).toList();
    }

    private void printTrace(AgentAnswer a) {
        System.out.println("[Demo] stopReason=" + a.getStopReason()
                + " degraded=" + a.isDegraded()
                + " steps=" + a.getSteps().size()
                + " tokens=" + a.getTotalTokens()
                + " 耗时=" + a.getTotalMs() + "ms");
        for (AgentStep s : a.getSteps()) {
            System.out.println("[Demo]   step" + s.getStepNo() + " " + s.getToolName()
                    + "(" + s.getArguments() + ") -> " + s.getStatus() + " " + s.getDurationMs() + "ms");
        }
        System.out.println("[Demo] 答案=" + a.getAnswer());
    }
}
