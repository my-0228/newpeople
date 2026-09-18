package com.freshman.rag.store;

import com.freshman.entity.KbChunk;
import com.freshman.entity.KbDocument;

import java.util.List;
import java.util.Optional;

/**
 * 知识库持久化端口
 *
 * 为什么要有这层抽象（而不是让索引器直接用 Mapper）：
 * 增量重建的核心逻辑是**有状态的**（"读旧向量 → 删旧 chunk → 复用未变内容的向量"），
 * 若索引器直接依赖 MyBatis-Plus 的 BaseMapper，单元测试就只能 mock 出一堆空返回值，
 * 无法真实验证复用/跳过/失败隔离这些关键行为，只能写出同义反复的测试。
 * 有了这个窄接口，测试可以注入内存实现，断言真实的读写序列。
 *
 * 所属模块：AI 智能问答模块 / RAG
 */
public interface KbStore {

    /** 按 (sourceType, sourceId) 查文档 */
    Optional<KbDocument> findDocument(String sourceType, Long sourceId);

    /** 保存文档（id 为空则插入，否则更新），返回文档 id */
    Long saveDocument(KbDocument doc);

    /** 取某文档下的全部 chunk（用于读取旧向量） */
    List<KbChunk> findChunks(Long documentId);

    /** 删除某文档下的全部 chunk */
    void deleteChunks(Long documentId);

    /** 插入一个 chunk */
    void insertChunk(KbChunk chunk);

    /** 更新文档的 chunk_count */
    void updateChunkCount(Long documentId, int chunkCount);
}
