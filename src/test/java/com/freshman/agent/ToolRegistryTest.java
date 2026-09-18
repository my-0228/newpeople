package com.freshman.agent;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ToolRegistry 单元测试。
 * 重点：**配置类错误必须在启动期就炸**（重名/命名/缺 schema），
 * 因为这类错误到了运行期表现为"模型莫名不调用某个工具"，极难排查。
 */
class ToolRegistryTest {

    /** 可定制的假工具 */
    static class FakeTool implements AgentTool {
        String name = "fake_tool";
        String description = "假工具";
        Map<String, Object> parameters = defaultSchema();

        static Map<String, Object> defaultSchema() {
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "object");
            schema.put("properties", Map.of());
            return schema;
        }

        @Override public String name() { return name; }
        @Override public String description() { return description; }
        @Override public Map<String, Object> parameters() { return parameters; }
        @Override public ToolResult execute(Map<String, Object> args, ToolContext ctx) {
            return ToolResult.ok("ok");
        }
    }

    @Test
    void rejectsDuplicateToolNames() {
        FakeTool a = new FakeTool();
        FakeTool b = new FakeTool();
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new ToolRegistry(List.of(a, b)));
        assertTrue(e.getMessage().contains("重名"), "报错应说明重名：" + e.getMessage());
    }

    @Test
    void rejectsInvalidName() {
        FakeTool bad = new FakeTool();
        bad.name = "Bad Name";
        assertThrows(IllegalStateException.class, () -> new ToolRegistry(List.of(bad)));

        FakeTool tooShort = new FakeTool();
        tooShort.name = "ab";
        assertThrows(IllegalStateException.class, () -> new ToolRegistry(List.of(tooShort)));
    }

    @Test
    void rejectsMissingDescription() {
        FakeTool bad = new FakeTool();
        bad.description = "  ";
        assertThrows(IllegalStateException.class, () -> new ToolRegistry(List.of(bad)));
    }

    @Test
    void rejectsSchemaWithoutObjectTypeOrProperties() {
        FakeTool bad = new FakeTool();
        bad.parameters = Map.of("properties", Map.of());
        assertThrows(IllegalStateException.class, () -> new ToolRegistry(List.of(bad)));

        FakeTool bad2 = new FakeTool();
        bad2.parameters = Map.of("type", "object");
        assertThrows(IllegalStateException.class, () -> new ToolRegistry(List.of(bad2)));
    }

    @Test
    void buildsOpenAiCompatibleToolsArray() {
        ToolRegistry registry = new ToolRegistry(List.of(new FakeTool()));

        JSONArray arr = JSONUtil.parseArray(registry.toolsJson());

        assertEquals(1, arr.size());
        JSONObject one = arr.getJSONObject(0);
        assertEquals("function", one.getStr("type"));
        JSONObject fn = one.getJSONObject("function");
        assertEquals("fake_tool", fn.getStr("name"));
        assertEquals("假工具", fn.getStr("description"));
        assertEquals("object", fn.getJSONObject("parameters").getStr("type"));
    }

    @Test
    void getReturnsEmptyForUnknownToolInsteadOfThrowing() {
        ToolRegistry registry = new ToolRegistry(List.of(new FakeTool()));

        assertTrue(registry.get("no_such_tool").isEmpty(),
                "未命中要返回 empty —— 由编排层转成 unknown_tool 回填给模型自愈，而不是抛异常");
        assertTrue(registry.get(null).isEmpty());
        assertTrue(registry.get("fake_tool").isPresent());
        assertEquals(List.of("fake_tool"), registry.names());
    }

    @Test
    void emptyToolListIsAllowed() {
        ToolRegistry registry = new ToolRegistry(List.of());
        assertEquals(0, registry.size());
        assertEquals("[]", registry.toolsJson());
    }
}
