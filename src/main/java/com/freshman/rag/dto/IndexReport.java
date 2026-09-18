package com.freshman.rag.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 索引重建报告
 *
 * 计数语义（钉死，测试按此断言）：
 *  - documentCount：本次处理的文档数
 *  - chunkCount   ：本次**实际落库**的 chunk 数
 *  - embeddedCount：本次**真正调用 Embedding API** 的 chunk 数（复用旧向量的不计）
 *  - skippedCount ：**按 chunk 计** —— 整篇文档因 content_hash 未变被跳过时，
 *                   把该文档上次的 chunkCount 累加进来（不是按文档数计）
 *  - failedCount  ：向量化失败（落库时 status=2）的 chunk 数
 *
 * 推论：第二次 rebuildAll(false) 应满足 skippedCount == 第一次的 chunkCount，
 * 且本次 chunkCount == 0、embeddedCount == 0；rebuildAll(true) 时 skippedCount == 0。
 *
 * 所属模块：AI 智能问答模块 / RAG
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class IndexReport {

    /** 本次处理的文档数 */
    private int documentCount;

    /** 本次实际落库的 chunk 数 */
    private int chunkCount;

    /** 本次真正调用 Embedding API 的 chunk 数 */
    private int embeddedCount;

    /** 因文档未变更而跳过的 chunk 数（按 chunk 计） */
    private int skippedCount;

    /** 向量化失败（status=2）的 chunk 数 */
    private int failedCount;

    /** 总耗时（毫秒） */
    private long costMs;
}
