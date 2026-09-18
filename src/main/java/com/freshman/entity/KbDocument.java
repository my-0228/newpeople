package com.freshman.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * RAG 知识文档实体类
 * 功能：一个业务来源对应一条文档记录，承载引用展示所需的标题与跳转路径，
 *       并通过 content_hash 支撑增量重建（内容未变则跳过整篇）
 *
 * 所属模块：AI 智能问答模块 / RAG 数据层
 * @author AI Module Team
 * @version 1.0
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("kb_document")
public class KbDocument {

    /** 文档ID，自增主键 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 来源类型：ai_knowledge/guide_faq/guide_registration_step/... 见规格 §5.7 映射表 */
    private String sourceType;

    /** 来源业务表主键 */
    private Long sourceId;

    /** 文档标题，用于引用展示 */
    private String title;

    /** 分类，复用现有分类口径 */
    private String category;

    /** 引用跳转路径，可为空（ai_knowledge 无独立页面） */
    private String urlPath;

    /** 正文 SHA-256，用于增量重建时跳过未变更文档 */
    private String contentHash;

    /** 该文档的分块数 */
    private Integer chunkCount;

    /** 状态：0-禁用 1-启用 */
    private Integer status;

    /** 创建时间 */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    /** 更新时间 */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
