package com.freshman.agent.dto;

import lombok.Data;

/**
 * 一次工具调用步骤（供前端轨迹卡片与落库）
 *
 * 所属模块：DeepSeek 问答 / Agent
 */
@Data
public class AgentStep {

    /** 第几步（从 1 开始） */
    private int stepNo;

    /** 工具名 */
    private String toolName;

    /** 模型给出的原始参数 JSON 字符串 */
    private String arguments;

    /** 结果摘要（截断后） */
    private String resultDigest;

    /** 耗时（毫秒） */
    private int durationMs;

    /** success / error / invalid_arguments / unknown_tool / place_not_found / place_ambiguous */
    private String status;

    /** 失败原因 */
    private String error;

    /**
     * 回填给模型的 `role:tool` 消息内容（**不落库** —— 落库用 {@link #resultDigest}）。
     * 放在本 DTO 上是权宜：它由编排层在构造步骤时一并产出，避免再引入一层包装类型。
     */
    private String backfillContent;

    /**
     * 工具结果的结构化附加信息（**不落库**）。
     * `search_knowledge` 会在其中放 `materials`，供上层生成引用列表。
     */
    private java.util.Map<String, Object> meta;
}
