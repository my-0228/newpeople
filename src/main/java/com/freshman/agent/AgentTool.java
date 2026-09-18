package com.freshman.agent;

import java.util.Map;

/**
 * Agent 工具契约
 *
 * 每个工具实现是一个 Spring Bean，由 {@link ToolRegistry} 自动收集。
 * **实现必须是只读的**（不写库、不发消息、不改状态）—— 这是本设计的硬性安全约束。
 *
 * 所属模块：DeepSeek 问答 / Agent
 */
public interface AgentTool {

    /** 工具唯一名，须匹配 ^[a-z_]{3,40}$ */
    String name();

    /** 给模型看的自然语言说明 —— 它决定模型是否/何时调用本工具，要写清"何时用" */
    String description();

    /** JSON Schema 参数定义（Hutool JSONObject 手工构造），须含 type/properties */
    Map<String, Object> parameters();

    /**
     * 执行工具。
     * 约定：**业务性失败（未找到、参数不合法）也返回 ToolResult.success=false，
     * 不要抛异常** —— 由编排层回填给模型让它自愈；抛异常只用于真正的意外错误。
     */
    ToolResult execute(Map<String, Object> args, ToolContext ctx);
}
