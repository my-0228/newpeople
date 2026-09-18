package com.freshman.agent.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * Agent 问答响应
 *
 * **与 `AiQaService.ChatResponse` 并存而不是改它**（规格硬性约束）：
 * 前者只服务于 AI 问答的 RAG 路径，本 DTO 专供 `/api/deepseek/chat`。
 * 前 6 个字段名与旧响应**完全一致**，因此前端旧逻辑不用改。
 *
 * 所属模块：DeepSeek 问答 / Agent
 */
@Data
public class AgentChatResponse {

    // ---------- 与旧响应同名字段（前端兼容） ----------
    private String question;
    private String answer;
    private Double confidence;
    private String category;
    private Boolean isUnknown;
    private String[] relatedQuestions;

    // ---------- Agent 专有字段 ----------
    /** 本次提问的唯一标识（用于轨迹查询） */
    private String turnId;

    /** 工具调用轨迹（前端渲染轨迹卡片） */
    private List<AgentStep> steps = new ArrayList<>();

    /** 引用来源（由 search_knowledge 的 materials 投影而来） */
    private List<CitationView> citations = new ArrayList<>();

    /** final_answer / max_steps / budget_exhausted / degraded */
    private String stopReason;

    /** 是否降级（未配置或调用失败，退回无工具 chat） */
    private Boolean degraded;

    /** 总耗时（毫秒） */
    private Long costMs;

    /** 引用视图（只暴露前端展示所需字段） */
    @Data
    public static class CitationView {
        private int index;
        private String title;
        private String urlPath;
        private String snippet;
        private String sourceType;
        private Long sourceId;
    }
}
