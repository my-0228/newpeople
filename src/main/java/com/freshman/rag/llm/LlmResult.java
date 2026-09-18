package com.freshman.rag.llm;

/**
 * LLM 调用结果
 *
 * 约定：**失败也是"正常返回值"**（success=false + error 说明），不是异常。
 * 这样编排层可以用一段直线逻辑处理成功/失败，避免 try-catch 撒在各处。
 *
 * 所属模块：AI 智能问答模块 / RAG
 *
 * @param success     是否成功拿到正文
 * @param content     模型返回的正文（失败时为 null）
 * @param totalTokens token 用量（供成本观察；未知时为 0）
 * @param costMs      本次调用耗时（毫秒）
 * @param error       失败原因（面向日志，不直接展示给用户）
 */
public record LlmResult(boolean success, String content, int totalTokens, long costMs, String error) {

    public static LlmResult ok(String content, int totalTokens, long costMs) {
        return new LlmResult(true, content, totalTokens, costMs, null);
    }

    public static LlmResult fail(String error, long costMs) {
        return new LlmResult(false, null, 0, costMs, error);
    }
}
