package com.freshman.rag.dto;

import lombok.Data;

/**
 * 引用来源（答案里的 [n] 对应此处第 n 条）
 *
 * 前端据此渲染"参考来源"折叠块；有 urlPath 的可点击跳转，
 * 无 urlPath 的（如 ai_knowledge）展开 snippet 原文。
 *
 * 所属模块：AI 智能问答模块 / RAG
 */
@Data
public class Citation {

    /** 引用编号（从 1 开始，对应答案中的 [n]） */
    private int index;

    /** 来源标题（kb_document.title） */
    private String title;

    /** 跳转路径，可为 null */
    private String urlPath;

    /** 摘要（≤200 字），供前端展开原文 */
    private String snippet;

    /** 来源类型，如 ai_knowledge / life_dormitory */
    private String sourceType;

    /** 来源业务表主键 */
    private Long sourceId;

    /** kb_chunk.id（便于排查与评估） */
    private Long chunkId;

    /**
     * 该材料在当前 gateMode 下的**原始分**（vectorCosine 或 keywordScore）。
     * **不是 RRF 分** —— 前端展示的"相似度"用这个。
     */
    private Double score;
}
