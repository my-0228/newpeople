package com.freshman.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.freshman.entity.KbChunk;
import org.apache.ibatis.annotations.Mapper;

/**
 * RAG 知识分块 Mapper
 * 所属模块：AI 智能问答模块 / RAG 数据层
 */
@Mapper
public interface KbChunkMapper extends BaseMapper<KbChunk> {
}
