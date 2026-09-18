package com.freshman.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.freshman.entity.AiRetrievalLog;
import org.apache.ibatis.annotations.Mapper;

/**
 * RAG 检索日志 Mapper
 * 所属模块：AI 智能问答模块 / RAG 数据层
 */
@Mapper
public interface AiRetrievalLogMapper extends BaseMapper<AiRetrievalLog> {
}
