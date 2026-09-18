package com.freshman.agent.tool;

import com.freshman.agent.AgentTool;
import com.freshman.agent.ToolContext;
import com.freshman.agent.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 通用工具（时间/算术）单元测试。
 * 算术工具的安全性是重点：**只能算四则运算，绝不能执行任意代码**。
 */
class UtilityToolsTest {

    private final UtilityToolsConfig config = new UtilityToolsConfig();
    private final AgentTool time = config.getCurrentTimeTool();
    private final AgentTool calc = config.calculateTool();
    private final ToolContext ctx = new ToolContext("s", null, "127.0.0.1");

    private ToolResult run(AgentTool t, Map<String, Object> args) {
        return t.execute(args, ctx);
    }

    @Test
    void toolNamesAndSchemasAreValid() {
        assertEquals("get_current_time", time.name());
        assertEquals("calculate", calc.name());
        assertEquals("object", time.parameters().get("type"));
        assertEquals("object", calc.parameters().get("type"));
        assertTrue(calc.parameters().containsKey("properties"));
    }

    @Test
    void currentTimeReturnsReadableValue() {
        ToolResult r = run(time, Map.of());
        assertTrue(r.success());
        assertNotNull(r.content());
        assertTrue(r.content().contains("当前时间"));
        assertNotNull(r.meta().get("date"), "应带结构化日期，便于模型后续推算");
    }

    @Test
    void calculateHandlesBasicExpressions() {
        assertEquals("12*3+1 = 37", run(calc, Map.of("expression", "12*3+1")).content());
        assertEquals("(3+4)*2 = 14", run(calc, Map.of("expression", "(3+4)*2")).content());
        assertEquals("14*2 = 28", run(calc, Map.of("expression", "14*2")).content());
        // 小数
        assertTrue(run(calc, Map.of("expression", "7/2")).content().contains("3.5"));
    }

    @Test
    void calculateRejectsUnsupportedCharactersInsteadOfExecuting() {
        for (String evil : new String[]{"1;rm -rf /", "System.exit(0)", "java.lang.Runtime",
                "1+abc", "process.exit()", "__import__('os')", "1 & 2", "new Date()"}) {
            ToolResult r = run(calc, Map.of("expression", evil));
            assertFalse(r.success(), "必须拒绝非算术表达式：" + evil);
            assertTrue(r.content().contains("不支持的字符") || r.content().contains("无法解析"),
                    "应给出明确拒绝原因，实际：" + r.content());
        }
    }

    @Test
    void calculateHandlesDivisionByZeroWithoutThrowing() {
        ToolResult r = assertDoesNotThrow(() -> run(calc, Map.of("expression", "1/0")));
        assertFalse(r.success(), "除零应返回失败而不是抛异常");
    }

    @Test
    void calculateHandlesMissingOrMalformedInput() {
        assertFalse(run(calc, Map.of()).success(), "缺参数应失败");
        assertFalse(run(calc, Map.of("expression", "(1+2")).success(), "括号不配对应失败");
        assertFalse(run(calc, Map.of("expression", "1++")).success(), "尾部运算符应失败");
        assertFalse(run(calc, Map.of("expression", "")).success(), "空表达式应失败");
    }

    @Test
    void calculateRejectsOverlyLongInput() {
        String longExpr = "1+".repeat(200) + "1";
        assertFalse(run(calc, Map.of("expression", longExpr)).success(), "超长输入应被拒绝");
    }
}
