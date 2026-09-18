package com.freshman.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * RAG 检索日志实体类
 * 功能：记录每次问答的召回与门限决策，是本项目"可观测性"的落点。
 *
 * 关键字段语义（不可混淆）：
 *  - gate_mode：本次请求实际使用的门限模式（vector / keyword）
 *  - top_score：**当前 gate_mode 下 top1 的原始分**（vectorCosine 或 keywordScore），
 *              不是 RRF 融合分（RRF 分只用于排序，值域约 0.016~0.033）
 *  - generate_ms 为 NULL 表示**未调用 LLM**（即走了拒答路径）
 *
 * 所属模块：AI 智能问答模块 / RAG 数据层
 * @author AI Module Team
 * @version 1.0
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("ai_retrieval_log")
public class AiRetrievalLog {

    /** 日志ID，自增主键 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 会话标识（UUID） */
    private String sessionId;

    /** 用户原始问题 */
    private String question;

    /** 向量路径召回命中数 */
    private Integer vectorHits;

    /** 关键词路径召回命中数 */
    private Integer keywordHits;

    /** 本次请求的门限模式：vector / keyword */
    private String gateMode;

    /** 当前 gate_mode 下 top1 的原始分（vectorCosine 或 keywordScore），不是 RRF 分 */
    private BigDecimal topScore;

    /** 最终进入 prompt 的 chunk id，逗号分隔 */
    private String finalChunkIds;

    /** 是否拒答：0-正常回答 1-拒答 */
    private Integer isUnknown;

    /** 是否降级回答：0-否 1-是 */
    private Integer degraded;

    /** Embedding 调用耗时（毫秒） */
    private Integer embeddingMs;

    /** 检索融合耗时（毫秒） */
    private Integer retrievalMs;

    /** 生成耗时（毫秒）；NULL 表示未调用 LLM（拒答路径） */
    private Integer generateMs;

    /** 创建时间 */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
