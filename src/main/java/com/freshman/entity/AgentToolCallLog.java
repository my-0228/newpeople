package com.freshman.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Agent 工具调用轨迹实体类
 *
 * 承载"Agent 身份"：`ai_chat_history` 没有 category 列，
 * 因此"这次问答是否用了 Agent、用了哪些工具"由本表回答。
 *
 * 所属模块：DeepSeek 问答 / Agent
 * @author DeepSeek Module
 * @version 1.0
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("agent_tool_call_log")
public class AgentToolCallLog {

    /** 轨迹ID，自增主键 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 一次提问的唯一标识（UUID），同一次提问的多步共享 */
    private String turnId;

    /** 会话标识 */
    private String sessionId;

    /** 第几步 */
    private Integer stepNo;

    /** 工具名 */
    private String toolName;

    /** 模型给出的原始参数 JSON 字符串 */
    private String arguments;

    /** 结果摘要（截断后） */
    private String resultDigest;

    /** 耗时（毫秒） */
    private Integer durationMs;

    /** success/error/invalid_arguments/unknown_tool/place_not_found/place_ambiguous */
    private String status;

    /** 失败原因 */
    private String error;

    /** 创建时间 */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
