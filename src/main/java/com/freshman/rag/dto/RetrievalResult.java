package com.freshman.rag.dto;

import lombok.Data;

import java.util.List;

/**
 * 混合检索结果 —— **跨规格契约**
 *
 * 子系统 B（DeepSeek Agent）的 search_knowledge 工具用
 * {@link #hasQualifiedMaterial} 判断"知识库无覆盖"（**不是** `chunks.isEmpty()`：
 * 门限会剔除不合格候选，两者语义不同）。
 *
 * 所属模块：AI 智能问答模块 / RAG
 */
@Data
public class RetrievalResult {

    /** 通过门限、按 RRF 排序后的材料集（最多 topK 条） */
    private List<ScoredChunk> chunks;

    /** 门限判定结果；false 表示应拒答（不调用 LLM） */
    private boolean hasQualifiedMaterial;

    /** 本次使用的门限模式："vector" | "keyword" */
    private String gateMode;

    /**
     * 当前 gateMode 下 top1 的**原始分**（vectorCosine 或 keywordScore）。
     * **绝不是 RRF 分** —— RRF 值域仅约 0.016~0.033，拿它比门限会导致永远拒答。
     */
    private double topScore;

    /** 向量路径召回候选数（供 ai_retrieval_log） */
    private int vectorHits;

    /** 关键词路径召回候选数（供 ai_retrieval_log） */
    private int keywordHits;

    /** 问题向量化耗时（毫秒）；未走向量路径时为 0（供 ai_retrieval_log 的分段耗时） */
    private long embeddingMs;

    /**
     * 门限判定结果（显式访问器，避免 Lombok 生成读起来别扭的 isHasQualifiedMaterial()）。
     * 子系统 B 的 search_knowledge 工具按此命名调用。
     */
    public boolean hasQualifiedMaterial() {
        return hasQualifiedMaterial;
    }
}
