package com.freshman.agent.llm;

import java.util.List;
import java.util.Map;

/**
 * DeepSeek 对话客户端（带 tools 的 function-calling 调用）
 *
 * 抽成接口的硬性理由：编排循环的 8 个边界情况（并行 tool_calls、幻觉工具名、
 * 非法 JSON、超步数…）若靠真实 API 验证，既慢又不确定；注入"脚本化客户端"
 * 就能在不花钱的前提下把每条分支都跑一遍。
 *
 * 与 RAG 的 `com.freshman.rag.llm.LlmClient` **有意独立**：配置命名空间不同、
 * 请求体不同（本接口要带 tools）、失败语义不同（本模块降级为无工具 chat）。
 *
 * 所属模块：DeepSeek 问答 / Agent
 */
public interface DeepSeekClient {

    /**
     * 一次调用。
     * 约定：**失败也是正常返回值**（error 非空），不抛异常。
     *
     * @param messages  OpenAI 协议消息数组（含 system/user/assistant/tool）
     * @param toolsJson OpenAI 兼容的 tools 数组 JSON；null 表示不带工具
     */
    ChatTurn chat(List<Map<String, Object>> messages, String toolsJson);

    /** 是否已配置（开关 + apiKey + apiUrl） */
    default boolean isConfigured() {
        return true;
    }

    /**
     * 一轮响应
     *
     * @param content      正文（可能有，也可能为空）
     * @param toolCalls    模型请求调用的工具（空表示可以出最终答案）
     * @param totalTokens  本轮 token 用量
     * @param finishReason 原始 finish_reason
     * @param error        失败原因（成功为 null）
     */
    record ChatTurn(String content, List<ToolCall> toolCalls, int totalTokens,
                    String finishReason, String error) {

        public static ChatTurn answer(String content, int tokens) {
            return new ChatTurn(content, List.of(), tokens, "stop", null);
        }

        public static ChatTurn tools(List<ToolCall> calls, int tokens) {
            return new ChatTurn(null, calls, tokens, "tool_calls", null);
        }

        public static ChatTurn fail(String error) {
            return new ChatTurn(null, List.of(), 0, "error", error);
        }

        public boolean failed() {
            return error != null;
        }

        public boolean hasToolCalls() {
            return toolCalls != null && !toolCalls.isEmpty();
        }

        /**
         * 一个工具调用请求
         *
         * @param id           工具调用 id —— **回填时必须与 assistant 消息里的 id 精确一致**，
         *                     否则下一轮 API 会返回 400
         * @param name         工具名（可能是模型幻觉出来的、注册表里并不存在的名字）
         * @param argumentsJson 参数的 **JSON 字符串**（不是对象），需二次解析
         */
        public record ToolCall(String id, String name, String argumentsJson) {}
    }
}
