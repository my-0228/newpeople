package com.freshman.rag.dto;

import lombok.Data;

/**
 * 检索结果 —— **跨规格契约**
 *
 * 子系统 B（DeepSeek Agent）的 search_knowledge 工具依赖本类的
 * title / urlPath / snippet / sourceType / sourceId 构造"参考材料"。
 * 因此这些字段**不得随意改名或删除**。
 *
 * 三种分数的语义严格区分、不可混用（详见规格 §5.5.1）：
 *  - vectorCosine  ：向量路径的**原始余弦**，参与门限判断
 *  - keywordScore  ：关键词路径的**原始 TF-IDF 余弦**，参与门限判断
 *  - rrfScore      ：融合分（值域约 0.016~0.033），**仅用于排序，绝不参与门限**
 *    把 rrfScore 与 0.35 这类门限比较会导致永远拒答。
 *
 * 所属模块：AI 智能问答模块 / RAG
 */
@Data
public class ScoredChunk {

    /** kb_chunk.id */
    private Long chunkId;

    /** kb_document.id */
    private Long documentId;

    /** 来源类型，见规格 §5.7 映射表 */
    private String sourceType;

    /** 来源业务表主键 */
    private Long sourceId;

    /** 文档内序号 */
    private int chunkIndex;

    /** 文档标题，用于引用展示 */
    private String title;

    /** 引用跳转路径，可为 null（ai_knowledge 无独立页面） */
    private String urlPath;

    /** chunk 正文 */
    private String content;

    /** 截断至 200 字的摘要，供引用卡片与工具结果使用 */
    private String snippet;

    /** 向量路径原始余弦（未命中向量路径时为 null） */
    private Double vectorCosine;

    /** 关键词路径原始 TF-IDF 余弦（未命中关键词路径时为 null） */
    private Double keywordScore;

    /** RRF 融合分；**仅用于排序** */
    private double rrfScore;
}
