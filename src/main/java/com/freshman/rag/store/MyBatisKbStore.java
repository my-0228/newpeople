package com.freshman.rag.store;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.freshman.entity.KbChunk;
import com.freshman.entity.KbDocument;
import com.freshman.mapper.KbChunkMapper;
import com.freshman.mapper.KbDocumentMapper;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * KbStore 的 MyBatis-Plus 实现
 *
 * 注意：BaseMapper 不支持 join，因此这里的每个方法都是单表操作，
 * 索引器的"两次查询"策略（先 chunk 再 document）在 VectorIndex 侧完成。
 *
 * 所属模块：AI 智能问答模块 / RAG
 */
@Component
public class MyBatisKbStore implements KbStore {

    private final KbDocumentMapper documentMapper;
    private final KbChunkMapper chunkMapper;

    public MyBatisKbStore(KbDocumentMapper documentMapper, KbChunkMapper chunkMapper) {
        this.documentMapper = documentMapper;
        this.chunkMapper = chunkMapper;
    }

    @Override
    public Optional<KbDocument> findDocument(String sourceType, Long sourceId) {
        List<KbDocument> list = documentMapper.selectList(new LambdaQueryWrapper<KbDocument>()
                .eq(KbDocument::getSourceType, sourceType)
                .eq(KbDocument::getSourceId, sourceId)
                .last("LIMIT 1"));
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    @Override
    public Long saveDocument(KbDocument doc) {
        if (doc.getId() == null) {
            documentMapper.insert(doc);
        } else {
            documentMapper.updateById(doc);
        }
        return doc.getId();
    }

    @Override
    public List<KbChunk> findChunks(Long documentId) {
        return chunkMapper.selectList(new LambdaQueryWrapper<KbChunk>()
                .eq(KbChunk::getDocumentId, documentId));
    }

    @Override
    public void deleteChunks(Long documentId) {
        chunkMapper.delete(new LambdaQueryWrapper<KbChunk>()
                .eq(KbChunk::getDocumentId, documentId));
    }

    @Override
    public void insertChunk(KbChunk chunk) {
        chunkMapper.insert(chunk);
    }

    @Override
    public void updateChunkCount(Long documentId, int chunkCount) {
        KbDocument patch = new KbDocument();
        patch.setId(documentId);
        patch.setChunkCount(chunkCount);
        documentMapper.updateById(patch);
    }
}
