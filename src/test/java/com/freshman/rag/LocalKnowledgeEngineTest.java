package com.freshman.rag;

import com.freshman.entity.AiKnowledge;
import com.freshman.mapper.AiKnowledgeMapper;
import com.freshman.rag.tokenizer.ChineseTokenizer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * LocalKnowledgeEngine 单元测试 —— **降级路径的行为一致性保护**。
 *
 * 引擎里的代码就是改造前 AiQaServiceImpl 的本地匹配（原样抽出），
 * 因此这些断言等价于"改造前的行为没被改坏"：
 *  - 命中问题 → 返回对应答案
 *  - 低于阈值 0.25 → 返回 empty（拒答）
 *  - **priority 加成仍然生效**（这是 KeywordRetriever 无法承担降级职责的原因：
 *    kb_chunk 没有 priority 列）
 */
class LocalKnowledgeEngineTest {

    private AiKnowledgeMapper mapper;
    private LocalKnowledgeEngine engine;

    @BeforeEach
    void setUp() {
        mapper = mock(AiKnowledgeMapper.class);
        engine = new LocalKnowledgeEngine(mapper, new ChineseTokenizer());
    }

    private static AiKnowledge doc(long id, String question, String answer, String category,
                                  String keywords, String synonyms, int priority) {
        AiKnowledge k = new AiKnowledge();
        k.setId(id);
        k.setQuestion(question);
        k.setAnswer(answer);
        k.setCategory(category);
        k.setKeywords(keywords);
        k.setSynonyms(synonyms);
        k.setPriority(priority);
        k.setStatus(1);
        return k;
    }

    private void load(List<AiKnowledge> docs) {
        when(mapper.selectAllEnabled()).thenReturn(docs);
        engine.reload();
    }

    @Test
    void exactQuestionMatchReturnsItsAnswer() {
        load(List.of(
                doc(1L, "宿舍有空调吗？", "厚德学区配备空调。", "宿舍", "空调,宿舍", "寝室有空调吗", 8),
                doc(2L, "军训多长时间？", "军训为期两周。", "军训", "军训,时间", "军训几天", 9)));

        Optional<LocalAnswerProvider.LocalAnswer> r = engine.best("宿舍有空调吗？");

        assertTrue(r.isPresent(), "完全匹配的问题应命中");
        assertEquals("厚德学区配备空调。", r.get().answer());
        assertEquals("宿舍", r.get().category());
        assertEquals(1L, r.get().knowledgeId());
    }

    @Test
    void synonymSpeakingAlsoMatches() {
        load(List.of(doc(1L, "宿舍有空调吗？", "厚德学区配备空调。", "宿舍", "空调,宿舍", "寝室有空调吗", 8)));

        // 「寝室」是同义词，扩展后应能命中
        assertTrue(engine.best("寝室有空调吗").isPresent(), "同义词表达应能命中");
    }

    @Test
    void unrelatedQuestionBelowThresholdReturnsEmpty() {
        load(List.of(doc(1L, "宿舍有空调吗？", "厚德学区配备空调。", "宿舍", "空调,宿舍", "寝室有空调吗", 8)));

        assertTrue(engine.best("学校有没有高尔夫球场").isEmpty(),
                "低于置信度阈值 0.25 应返回 empty，交由上层拒答");
    }

    @Test
    void priorityBonusStillAppliesWhenEverythingElseIsEqual() {
        // 两条知识的内容特征完全相同，只有 priority 不同 → 高优先级应胜出
        load(List.of(
                doc(1L, "宿舍有空调吗？", "低优先级答案", "宿舍", "空调,宿舍", "寝室有空调吗", 0),
                doc(2L, "宿舍有空调吗？", "高优先级答案", "宿舍", "空调,宿舍", "寝室有空调吗", 9)));

        Optional<LocalAnswerProvider.LocalAnswer> r = engine.best("宿舍有空调吗？");

        assertTrue(r.isPresent());
        assertEquals(2L, r.get().knowledgeId(),
                "priority 加成必须仍然生效（这正是它不能由 KeywordRetriever 承担降级职责的原因）");
        assertEquals("高优先级答案", r.get().answer());
    }

    @Test
    void emptyKnowledgeBaseAndBlankQuestionAreSafe() {
        load(List.of());
        assertTrue(engine.best("宿舍").isEmpty());
        assertEquals(0, engine.knowledgeSize());

        load(List.of(doc(1L, "宿舍有空调吗？", "答案", "宿舍", "空调", "寝室", 1)));
        assertTrue(engine.best("").isEmpty());
        assertTrue(engine.best(null).isEmpty());
    }

    @Test
    void statsAndCategoriesAreExposed() {
        load(List.of(doc(1L, "宿舍有空调吗？", "答案", "宿舍", "空调", "寝室", 1)));

        assertTrue(engine.knowledgeSize() > 0);
        assertTrue(engine.vocabularySize() > 0);
        assertTrue(engine.categories().length > 0, "应暴露分类列表供 /api/ai/categories 使用");
        assertTrue(engine.stats().containsKey("threshold"));
        assertTrue(engine.stats().containsKey("weights"));
    }
}
