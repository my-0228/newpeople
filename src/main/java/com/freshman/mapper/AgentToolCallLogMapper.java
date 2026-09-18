package com.freshman.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.freshman.entity.AgentToolCallLog;
import org.apache.ibatis.annotations.Mapper;

/**
 * Agent 工具调用轨迹 Mapper
 * 所属模块：DeepSeek 问答 / Agent
 */
@Mapper
public interface AgentToolCallLogMapper extends BaseMapper<AgentToolCallLog> {
}
