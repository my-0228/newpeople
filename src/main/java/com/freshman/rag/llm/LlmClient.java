package com.freshman.rag.llm;

/**
 * 生成层抽象
 *
 * 抽成接口的**硬性理由**：G4 要求断言"拒答路径**不调用 LLM**"，
 * 这只有在 LLM 客户端可注入时才写得出（注入计数实现即可）。
 * 另外它也把"HTTP 细节"与"RAG 编排"分开，便于故障注入测试。
 *
 * 与子系统 B 的 DeepSeekClient 的关系：**有意独立**。
 * 两者协议同为 OpenAI 兼容，但配置命名空间不同、请求体不同（Agent 需带 tools）、
 * 失败语义不同（本项目降级到本地引擎，Agent 降级到无工具 chat）。
 *
 * 所属模块：AI 智能问答模块 / RAG
 */
public interface LlmClient {

    /** 单轮补全；**任何异常都必须被实现吞掉并转成 success=false 的结果**，不得向调用方抛 */
    LlmResult complete(String systemPrompt, String userMessage);

    /** 是否已配置可用（开关打开且 apiKey 非空）；未配置时编排层直接走降级 */
    default boolean isConfigured() {
        return true;
    }
}
