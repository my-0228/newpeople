package com.freshman.agent.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 编排结果
 *
 * 所属模块：DeepSeek 问答 / Agent
 */
@Data
public class AgentAnswer {

    /** 最终答案（可能来自模型，也可能是循环停止时的兜底摘要） */
    private String answer;

    /** 工具调用轨迹 */
    private List<AgentStep> steps = new ArrayList<>();

    /** final_answer / max_steps / budget_exhausted / degraded */
    private String stopReason = "final_answer";

    /** 是否降级（未配置或调用失败，退回无工具 chat） */
    private boolean degraded;

    /** 总耗时（毫秒） */
    private long totalMs;

    /** 累计 token */
    private int totalTokens;

    /** 一次提问的唯一标识（用于按 turnId 回放轨迹） */
    private String turnId;
}
