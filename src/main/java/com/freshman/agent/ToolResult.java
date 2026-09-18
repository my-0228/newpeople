package com.freshman.agent;

import java.util.Map;

/**
 * 工具执行结果
 *
 * `content` 会被回填成 OpenAI 协议里的 `role:tool` 消息，因此它应当是
 * **给模型看的文本**（可以是 JSON 字符串，也可以是可读中文提示）。
 *
 * 所属模块：DeepSeek 问答 / Agent
 *
 * @param success 是否成功（业务性失败为 false，且不抛异常）
 * @param content 回填给模型的内容
 * @param meta    结构化附加信息（供轨迹日志摘要使用，不直接回填）
 */
public record ToolResult(boolean success, String content, Map<String, Object> meta) {

    public static ToolResult ok(String content) {
        return new ToolResult(true, content, Map.of());
    }

    public static ToolResult ok(String content, Map<String, Object> meta) {
        return new ToolResult(true, content, meta == null ? Map.of() : meta);
    }

    public static ToolResult fail(String content) {
        return new ToolResult(false, content, Map.of());
    }

    public static ToolResult fail(String content, Map<String, Object> meta) {
        return new ToolResult(false, content, meta == null ? Map.of() : meta);
    }
}
