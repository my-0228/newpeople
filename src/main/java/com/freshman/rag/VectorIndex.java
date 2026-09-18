package com.freshman.rag;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.freshman.entity.KbChunk;
import com.freshman.entity.KbDocument;
import com.freshman.mapper.KbChunkMapper;
import com.freshman.mapper.KbDocumentMapper;
import com.freshman.rag.dto.ScoredChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 内存向量索引
 *
 * 设计要点：
 * 1. **不引入向量库**：千级 chunk 下暴力余弦只需要约 100 万次乘加（<5ms），
 *    Milvus/pgvector 的运维成本远大于收益；十万级以上才需要 HNSW/IVF。
 * 2. **BaseMapper 不支持 join**，因此是两次查询：先查 status=1 的 chunk，
 *    再按 documentId 批量查 kb_document 补 title/urlPath/sourceType/sourceId。
 * 3. **热重建原子切换**：在新数组上构建，完成后原子替换 volatile 引用，读路径无锁。
 * 4. **加载失败不阻断**：调用方（VectorIndexLoader）捕获异常；索引为空时
 *    检索层自动转为纯关键词模式（gate_mode=keyword）。
 *
 * 所属模块：AI 智能问答模块 / RAG
 * @author AI Module Team
 * @version 1.0
 */
@Component
public class VectorIndex {

    private static final Logger log = LoggerFactory.getLogger(VectorIndex.class);

    /** 引用摘要长度（供引用卡片与 Agent 工具结果使用） */
    private static final int SNIPPET_LEN = 200;

    private final KbChunkMapper chunkMapper;
    private final KbDocumentMapper documentMapper;
    private final RagProperties props;

    /** 当前快照；rebuild() 完成后原子替换 */
    private volatile Snapshot snapshot = Snapshot.EMPTY;

    public VectorIndex(KbChunkMapper chunkMapper, KbDocumentMapper documentMapper, RagProperties props) {
        this.chunkMapper = chunkMapper;
        this.documentMapper = documentMapper;
        this.props = props;
    }

    /** 索引条数（RagIndexIT 与健康检查依赖） */
    public int size() {
        return snapshot.metas.length;
    }

    /** 索引是否为空（为空时检索层转 keyword 门限模式） */
    public boolean isEmpty() {
        return snapshot.metas.length == 0;
    }

    /**
     * 全量重建索引：从 kb_chunk 读取已向量化分块 → 解析向量 → 原子替换快照。
     * 单条脏数据只跳过并告警，不影响其它条目，也不中断整体重建。
     */
    public synchronized void rebuild() {
        List<KbChunk> rows = chunkMapper.selectList(new LambdaQueryWrapper<KbChunk>()
                .eq(KbChunk::getStatus, 1)
                .isNotNull(KbChunk::getEmbedding));

        int expectedDim = props.getEmbedding().getDimensions();
        List<float[]> vectors = new ArrayList<>();
        List<ChunkMeta> metas = new ArrayList<>();
        int skipped = 0;

        for (KbChunk row : rows) {
            // 防御性过滤：即使查询条件被改动，也不把未向量化/失活的行放进索引
            if (row.getStatus() == null || row.getStatus() != 1
                    || row.getEmbedding() == null || row.getEmbedding().isBlank()) {
                skipped++;
                continue;
            }
            float[] v = parseVector(row.getEmbedding(), expectedDim, row.getId());
            if (v == null) {
                skipped++;
                continue;
            }
            vectors.add(v);
            metas.add(new ChunkMeta(row));
        }

        // 补文档元信息（BaseMapper 无 join，故批量二次查询），
        // 并**剔除文档缺失或已禁用（status != 1）的 chunk** ——
        // 否则业务行被删除/禁用后，这些 chunk 仍会被检索到并给出"幽灵答案"。
        int dropped = fillDocumentMetaAndDropOrphans(metas, vectors);

        snapshot = new Snapshot(vectors.toArray(new float[0][]), metas.toArray(new ChunkMeta[0]));
        log.info("[RAG] 向量索引已重建：{} 条（跳过脏数据 {} 条，文档缺失/禁用剔除 {} 条，维度 {}）",
                snapshot.metas.length, skipped, dropped, expectedDim);
    }

    /**
     * 向量召回：返回按原始余弦降序的 Top-K。
     * 索引为空时返回空列表（不抛异常）—— 上层的 gate_mode 会据此转为 keyword。
     */
    public List<ScoredChunk> search(float[] query, int topK) {
        Snapshot snap = this.snapshot;
        if (query == null || snap.metas.length == 0 || topK <= 0) {
            return List.of();
        }
        int k = Math.min(topK, snap.metas.length);

        // 先算全部得分（归一化向量的点积即余弦）
        // 维度不匹配的向量直接判为不可用（得 0 并跳过），而不是用 Math.min 静默打分
        final double[] scores = new double[snap.metas.length];
        int dimMismatch = 0;
        for (int i = 0; i < snap.metas.length; i++) {
            if (snap.vectors[i].length != query.length) {
                scores[i] = 0;
                dimMismatch++;
                continue;
            }
            scores[i] = dot(query, snap.vectors[i]);
        }
        if (dimMismatch > 0) {
            log.warn("[RAG] 查询向量维度 {} 与索引中 {} 条向量不一致，这些条目本次不参与召回",
                    query.length, dimMismatch);
            if (dimMismatch == snap.metas.length) {
                return List.of();
            }
        }

        // 固定大小最小堆取 Top-K，避免全量排序
        PriorityQueue<Integer> heap = new PriorityQueue<>(Comparator.comparingDouble(i -> scores[i]));
        for (int i = 0; i < snap.metas.length; i++) {
            if (heap.size() < k) {
                heap.offer(i);
            } else if (scores[i] > scores[heap.peek()]) {
                heap.poll();
                heap.offer(i);
            }
        }

        List<Integer> picked = new ArrayList<>(heap);
        picked.sort((a, b) -> Double.compare(scores[b], scores[a]));
        List<ScoredChunk> out = new ArrayList<>(picked.size());
        for (Integer i : picked) {
            out.add(toScoredChunk(snap.metas[i], scores[i]));
        }
        return out;
    }

    // ==================== 内部实现 ====================

    private ScoredChunk toScoredChunk(ChunkMeta meta, double cosine) {
        ScoredChunk sc = new ScoredChunk();
        sc.setChunkId(meta.chunkId);
        sc.setDocumentId(meta.documentId);
        sc.setSourceType(meta.sourceType);
        sc.setSourceId(meta.sourceId);
        sc.setChunkIndex(meta.chunkIndex);
        sc.setTitle(meta.title);
        sc.setUrlPath(meta.urlPath);
        sc.setContent(meta.content);
        sc.setSnippet(snippet(meta.content));
        sc.setVectorCosine(cosine);
        return sc;
    }

    private static String snippet(String content) {
        if (content == null) {
            return null;
        }
        return content.length() <= SNIPPET_LEN ? content : content.substring(0, SNIPPET_LEN);
    }

    /** 解析 JSON 向量；失败或维度不符返回 null（调用方计入 skipped） */
    private float[] parseVector(String text, int expectedDim, Long chunkId) {
        try {
            JSONArray arr = JSONUtil.parseArray(text);
            if (arr.size() != expectedDim) {
                log.warn("[RAG] chunk {} 向量维度 {} != 期望 {}，跳过", chunkId, arr.size(), expectedDim);
                return null;
            }
            float[] v = new float[arr.size()];
            for (int i = 0; i < arr.size(); i++) {
                v[i] = arr.getFloat(i);
            }
            return v;
        } catch (Exception e) {
            log.warn("[RAG] chunk {} 向量解析失败，跳过：{}", chunkId, e.getMessage());
            return null;
        }
    }

    /**
     * 批量补文档元信息，并剔除文档缺失或已禁用（status != 1）的 chunk。
     * metas 与 vectors 是平行数组，因此两者同步删除。
     *
     * @return 被剔除的条数
     */
    private int fillDocumentMetaAndDropOrphans(List<ChunkMeta> metas, List<float[]> vectors) {
        Set<Long> docIds = new HashSet<>();
        for (ChunkMeta m : metas) {
            if (m.documentId != null) {
                docIds.add(m.documentId);
            }
        }
        Map<Long, KbDocument> docMap = new HashMap<>();
        if (!docIds.isEmpty()) {
            for (KbDocument d : documentMapper.selectBatchIds(docIds)) {
                docMap.put(d.getId(), d);
            }
        }

        int dropped = 0;
        for (int i = metas.size() - 1; i >= 0; i--) {
            ChunkMeta m = metas.get(i);
            KbDocument d = m.documentId == null ? null : docMap.get(m.documentId);
            if (d == null || d.getStatus() == null || d.getStatus() != 1) {
                metas.remove(i);
                vectors.remove(i);
                dropped++;
                continue;
            }
            m.title = d.getTitle();
            m.urlPath = d.getUrlPath();
            m.sourceType = d.getSourceType();
            m.sourceId = d.getSourceId();
        }
        return dropped;
    }

    /** 归一化向量的点积即余弦相似度 */
    private static double dot(float[] a, float[] b) {
        int n = Math.min(a.length, b.length);
        double s = 0;
        for (int i = 0; i < n; i++) {
            s += (double) a[i] * b[i];
        }
        return s;
    }

    /** 索引条目元数据（与向量数组平行存放，避免每行一个对象） */
    static final class ChunkMeta {
        Long chunkId;
        Long documentId;
        Integer chunkIndex;
        String content;
        String title;
        String urlPath;
        String sourceType;
        Long sourceId;

        ChunkMeta(KbChunk row) {
            this.chunkId = row.getId();
            this.documentId = row.getDocumentId();
            this.chunkIndex = row.getChunkIndex();
            this.content = row.getContent();
        }
    }

    /** 不可变快照；volatile 引用的原子替换是"热重建不加锁"的关键 */
    static final class Snapshot {
        static final Snapshot EMPTY = new Snapshot(new float[0][], new ChunkMeta[0]);

        final float[][] vectors;
        final ChunkMeta[] metas;

        Snapshot(float[][] vectors, ChunkMeta[] metas) {
            this.vectors = vectors;
            this.metas = metas;
        }
    }
}
