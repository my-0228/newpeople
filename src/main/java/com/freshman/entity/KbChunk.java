package com.freshman.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * RAG 知识分块实体类
 * 功能：检索的最小单位。content 参与向量化；search_terms 只供关键词检索路径使用，
 *       **绝不拼进 content**（避免污染向量语义）。
 *
 * embedding 存 JSON float 数组文本（MySQL 5.6 无 JSON 列类型，故用 mediumtext）。
 * 写入前已做 L2 归一化，使检索时的点积等价于余弦相似度。
 *
 * 所属模块：AI 智能问答模块 / RAG 数据层
 * @author AI Module Team
 * @version 1.0
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("kb_chunk")
public class KbChunk {

    /** 分块ID，自增主键 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属文档ID */
    private Long documentId;

    /** 文档内序号，从 0 开始 */
    private Integer chunkIndex;

    /** 分块正文（参与向量化） */
    private String content;

    /** 关键词/同义词等检索辅助词（不参与向量化，仅供关键词路径） */
    private String searchTerms;

    /** 在原文中的起始偏移，用于引用溯源 */
    private Integer charStart;

    /** 在原文中的结束偏移 */
    private Integer charEnd;

    /** 分块正文 SHA-256，用于增量向量化（未变则复用旧向量） */
    private String contentHash;

    /** 向量：JSON float 数组文本，写入前已 L2 归一化 */
    private String embedding;

    /** 生成该向量的模型名，如 text-embedding-v3 */
    private String embeddingModel;

    /** 向量维度，如 1024 */
    private Integer dim;

    /** 状态：0-待向量化 1-已向量化 2-向量化失败 */
    private Integer status;

    /** 创建时间 */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    /** 更新时间 */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
