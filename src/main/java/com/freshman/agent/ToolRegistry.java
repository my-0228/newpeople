package com.freshman.agent;

import cn.hutool.json.JSONUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 工具注册表：收集所有 {@link AgentTool} Bean，校验并生成 OpenAI 兼容的 tools 数组
 *
 * 校验在**构造期**完成（重名/命名/schema 有问题直接让应用启动失败）——
 * 这类配置错误不该等到运行期才暴露。
 *
 * 所属模块：DeepSeek 问答 / Agent
 */
@Component
public class ToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);

    /** 工具名规范：小写字母与下划线，3~40 字符 */
    private static final Pattern NAME = Pattern.compile("^[a-z_]{3,40}$");

    /** OpenAI 协议上限：一次请求最多 128 个工具；本项目用不到那么多，但设个上界防呆 */
    private static final int MAX_TOOLS = 32;

    private final Map<String, AgentTool> tools = new LinkedHashMap<>();

    public ToolRegistry(List<AgentTool> beans) {
        if (beans != null) {
            for (AgentTool t : beans) {
                validate(t);
                if (tools.putIfAbsent(t.name(), t) != null) {
                    throw new IllegalStateException("Agent 工具重名：" + t.name()
                            + "（同名会让模型无法区分，且注册表会静默覆盖）");
                }
            }
        }
        if (tools.size() > MAX_TOOLS) {
            throw new IllegalStateException("Agent 工具数量超过上限 " + MAX_TOOLS + "：" + tools.size());
        }
        log.info("[Agent] 工具注册完成，共 {} 个：{}", tools.size(), tools.keySet());
    }

    /** 按名取工具；未命中返回 empty（由编排层转成 unknown_tool 回填，而不是抛异常） */
    public Optional<AgentTool> get(String name) {
        return Optional.ofNullable(name == null ? null : tools.get(name));
    }

    /** 全部工具名（用于 unknown_tool 回填时的 available 提示） */
    public List<String> names() {
        return new ArrayList<>(tools.keySet());
    }

    public int size() {
        return tools.size();
    }

    /** 生成 OpenAI 兼容的 tools 数组 JSON */
    public String toolsJson() {
        List<Map<String, Object>> arr = new ArrayList<>(tools.size());
        for (AgentTool t : tools.values()) {
            Map<String, Object> fn = new LinkedHashMap<>();
            fn.put("name", t.name());
            fn.put("description", t.description());
            fn.put("parameters", t.parameters());
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("type", "function");
            one.put("function", fn);
            arr.add(one);
        }
        return JSONUtil.toJsonStr(arr);
    }

    private static void validate(AgentTool t) {
        if (t.name() == null || !NAME.matcher(t.name()).matches()) {
            throw new IllegalStateException("Agent 工具名不合法（须匹配 ^[a-z_]{3,40}$）：" + t.name());
        }
        if (t.description() == null || t.description().isBlank()) {
            throw new IllegalStateException("Agent 工具 " + t.name() + " 缺少 description —— 模型靠它决定是否调用");
        }
        Map<String, Object> params = t.parameters();
        if (params == null || !"object".equals(params.get("type")) || !params.containsKey("properties")) {
            throw new IllegalStateException("Agent 工具 " + t.name()
                    + " 的 parameters 必须是含 type=object 与 properties 的 JSON Schema");
        }
    }
}
