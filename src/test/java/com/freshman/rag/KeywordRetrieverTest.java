package com.freshman.rag;

import com.freshman.entity.KbChunk;
import com.freshman.entity.KbDocument;
import com.freshman.mapper.KbChunkMapper;
import com.freshman.mapper.KbDocumentMapper;
import com.freshman.rag.dto.ScoredChunk;
import com.freshman.rag.tokenizer.ChineseTokenizer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * KeywordRetriever 单元测试（Mockito 模拟 Mapper，不连数据库）。
 * 覆盖：语料含 status=2、空白内容排除、status=0 排除、排序、得分值域、
 * topK 与字段填充、search_terms 参与匹配但不污染正文。
 */
class KeywordRetrieverTest {

    private KbChunkMapper chunkMapper;
    private KbDocumentMapper documentMapper;
    private KeywordRetriever retriever;

    @BeforeEach
    void setUp() {
        chunkMapper = mock(KbChunkMapper.class);
        documentMapper = mock(KbDocumentMapper.class);
        retriever = new KeywordRetriever(chunkMapper, documentMapper, new ChineseTokenizer());
    }

    private static KbChunk chunk(long id, long docId, Integer status, String content, String searchTerms) {
        KbChunk c = new KbChunk();
        c.setId(id);
        c.setDocumentId(docId);
        c.setChunkIndex(0);
        c.setContent(content);
        c.setSearchTerms(searchTerms);
        c.setStatus(status);
        return c;
    }

    private static KbDocument doc(long id, String title) {
        KbDocument d = new KbDocument();
        d.setId(id);
        d.setTitle(title);
        d.setSourceType("ai_knowledge");
        d.setSourceId(id);
        d.setUrlPath(null);
        return d;
    }

    private void stub(List<KbChunk> chunks, List<KbDocument> docs) {
        when(chunkMapper.selectList(any())).thenReturn(chunks);
        when(documentMapper.selectBatchIds(any())).thenReturn(docs);
    }

    /** 修正 0.2 的回归测试：向量化失败的块必须仍可被关键词路径检索到 */
    @Test
    void corpusIncludesStatus2ChunksSoFailedVectorsStayRetrievable() {
        stub(List.of(
                chunk(1L, 10L, 1, "宿舍有空调吗\n厚德学区配备空调。", null),
                chunk(2L, 10L, 2, "军训为期两周共14天。", null)),      // 向量化失败，但正文可用
                List.of(doc(10L, "宿舍与军训")));

        retriever.rebuild();

        assertEquals(2, retriever.size(), "status=2 的块必须进语料，否则它对所有检索路径永久不可见");
        List<ScoredChunk> hits = retriever.search("军训多少天", 5);
        assertTrue(hits.stream().anyMatch(h -> h.getChunkId() == 2L),
                "向量化失败的块应能被关键词路径命中");
    }

    @Test
    void blankContentAndDisabledStatusAreExcluded() {
        stub(List.of(
                chunk(1L, 10L, 1, "   ", null),          // 空白正文 → 排除
                chunk(2L, 10L, 1, null, null),           // null 正文 → 排除
                chunk(3L, 10L, 0, "被禁用的块内容", null), // status=0 → 排除
                chunk(4L, 10L, 1, "有效内容：食堂在哪", null)),
                List.of(doc(10L, "t")));

        retriever.rebuild();

        assertEquals(1, retriever.size());
    }

    @Test
    void cosineScoreRanksRelevantChunkFirst() {
        stub(List.of(
                chunk(1L, 10L, 1, "宿舍有空调吗\n厚德学区配备空调，其他学区暂未安装。", null),
                chunk(2L, 10L, 1, "军训为期两周共14天，包含队列训练与内务整理。", null)),
                List.of(doc(10L, "常见问题")));

        retriever.rebuild();
        List<ScoredChunk> hits = retriever.search("宿舍有空调吗", 5);

        assertFalse(hits.isEmpty());
        assertEquals(1L, hits.get(0).getChunkId(), "相关块应排第一");
        assertTrue(hits.get(0).getKeywordScore() > 0);
    }

    @Test
    void scoreIsNormalizedToZeroOneAndNoBaseScoreIsAdded() {
        stub(List.of(
                chunk(1L, 10L, 1, "完全无关的内容：图书馆开放时间。", null)),
                List.of(doc(10L, "t")));

        retriever.rebuild();
        List<ScoredChunk> hits = retriever.search("宿舍空调", 5);

        // 关键词路径**不得**有分类底分/priority 底分，所以完全无关的块不应被召回
        assertTrue(hits.isEmpty(), "无词项重叠时不应召回（存在底分才会凑出结果）：" + hits);

        // 有重叠时得分必须落在 (0,1]
        List<ScoredChunk> hits2 = retriever.search("图书馆开放时间", 5);
        assertFalse(hits2.isEmpty());
        for (ScoredChunk h : hits2) {
            assertTrue(h.getKeywordScore() > 0 && h.getKeywordScore() <= 1.0,
                    "得分应在 (0,1]，实际=" + h.getKeywordScore());
        }
    }

    @Test
    void topKIsHonouredAndOnlyKeywordScoreIsFilled() {
        stub(List.of(
                chunk(1L, 10L, 1, "宿舍有空调吗", null),
                chunk(2L, 10L, 1, "宿舍是几人间", null),
                chunk(3L, 10L, 1, "宿舍几点关门", null)),
                List.of(doc(10L, "宿舍")));

        retriever.rebuild();

        assertEquals(2, retriever.search("宿舍", 2).size(), "应受 topK 限制");
        assertEquals(3, retriever.search("宿舍", 10).size(), "topK 超过语料时应返回全部命中");

        ScoredChunk first = retriever.search("宿舍", 1).get(0);
        assertNotNull(first.getKeywordScore(), "关键词路径应填 keywordScore");
        assertNull(first.getVectorCosine(), "关键词路径不应填 vectorCosine");
        assertNotNull(first.getSnippet(), "引用卡片需要 snippet");
        assertEquals("宿舍", first.getTitle(), "应带上文档标题");
    }

    @Test
    void searchTermsParticipateInMatchingWithoutPollutingContent() {
        stub(List.of(
                chunk(1L, 10L, 1, "问题：宿舍条件如何", "寝室,住宿,公寓")),
                List.of(doc(10L, "宿舍")));

        retriever.rebuild();

        // search_terms 参与匹配 → 用同义词"寝室"也应命中
        List<ScoredChunk> hits = retriever.search("寝室", 5);
        assertFalse(hits.isEmpty(), "search_terms 应参与关键词匹配");
        assertEquals(1L, hits.get(0).getChunkId());

        // 但正文本身不能被 search_terms 污染（content 原样返回）
        assertFalse(hits.get(0).getContent().contains("寝室"),
                "search_terms 绝不能拼进正文：" + hits.get(0).getContent());
    }

    @Test
    void blankQuestionOrEmptyCorpusIsSafe() {
        stub(List.of(), List.of());
        retriever.rebuild();

        assertTrue(retriever.isEmpty());
        assertTrue(retriever.search("宿舍", 5).isEmpty());
        assertTrue(retriever.search("", 5).isEmpty());
        assertTrue(retriever.search(null, 5).isEmpty());
    }
}
