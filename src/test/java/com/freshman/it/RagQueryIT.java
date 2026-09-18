package com.freshman.it;

import com.freshman.rag.RagProperties;
import com.freshman.service.AiQaService;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RAG 问答的**真实端到端验证**：真实 MySQL（173 个已向量化 chunk）+ 真实 LLM。
 *
 * 运行（不设 key 会自动跳过，避免像早期那样把 it 层拖到超时）：
 *   $env:DASHSCOPE_API_KEY = "<阿里 key>"
 *   mvn test -Dtest=RagQueryIT -Dtest.excludedGroups=eval
 *
 * 成本：每个用例 1 次问答（约 1 次 embedding + 1 次 chat），全类在**分币级**。
 */
@Tag("it")
@SpringBootTest
class RagQueryIT {

    @Autowired
    private AiQaService aiQaService;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private RagProperties ragProperties;

    @BeforeEach
    void requireApiKey() {
        // 前置：数据层已建好（kb_chunk 里有已向量化的行）
        Integer withVector = jdbc.queryForObject(
                "SELECT COUNT(*) FROM kb_chunk WHERE status = 1 AND embedding IS NOT NULL", Integer.class);
        Assumptions.assumeTrue(withVector != null && withVector > 0,
                "kb_chunk 中没有已向量化的 chunk，请先跑 RagIndexIT（需要 DASHSCOPE_API_KEY）");

        String key = ragProperties.getEmbedding().getApiKey();
        Assumptions.assumeTrue(key != null && !key.isBlank(),
                "未设置 DASHSCOPE_API_KEY，跳过需要真实 API 的端到端问答验证");
    }

    /** 场景一：语料可答 → 有答案、有引用、可溯源 */
    @Test
    void answerableQuestionReturnsAnswerWithCitations() {
        String session = "it-query-" + UUID.randomUUID();

        AiQaService.ChatResponse resp = aiQaService.chat("宿舍有空调吗", session, "127.0.0.1", null);

        assertNotNull(resp.getAnswer());
        assertFalse(resp.getAnswer().isBlank(), "应有答案");
        assertEquals(Boolean.FALSE, resp.getIsUnknown(), "语料里有这个问题，不应判为未知");
        assertNotNull(resp.getCitations());
        assertFalse(resp.getCitations().isEmpty(), "RAG 答案应带引用来源");

        // 引用必须可溯源：标题非空，且至少能定位到来源类型
        var first = resp.getCitations().get(0);
        assertNotNull(first.getTitle(), "引用应有标题");
        assertNotNull(first.getSourceType(), "引用应带来源类型");
        assertNotNull(first.getSnippet(), "引用应带摘要供前端展开");
        assertTrue(first.getScore() != null && first.getScore() > 0, "引用应带原始相似度分");
        assertEquals(1, first.getIndex(), "首个引用编号应为 1");

        System.out.println("[RagQueryIT] 答案=" + resp.getAnswer());
        System.out.println("[RagQueryIT] 引用=" + resp.getCitations().size() + " 条，首条来源="
                + first.getTitle() + " score=" + first.getScore()
                + " urlPath=" + first.getUrlPath());
    }

    /**
     * 场景二：语料无覆盖的问题**必须不编造**。
     *
     * ⚠️ 实测校准（重要发现）：拒答是**两层机制**，不能混为一层断言：
     *  ① **门限级拒答**：向量原始余弦 < min-score(0.35) → 不调 LLM，直接拒答。
     *     但它拦不住"语义邻近但确实无覆盖"的问题 —— 实测"学校有没有高尔夫球场"
     *     对 `ai_knowledge` 的"学校有哪些运动场所？"余弦高达 **0.6439**，会通过门限。
     *  ② **prompt 级诚实声明**：材料不足以回答时，模型被要求明说"知识库暂未收录"。
     *     实测该问题正是被这一层接住：模型回答了"知识库暂未收录该问题"并列举了
     *     材料里真实存在的运动场所，**没有编造高尔夫球场**。
     *
     * 因此本用例断言的是"不编造"这一**最终性质**，而非某一层的具体路径；
     * 门限级拒答的精确语义由 `HybridRetrieverTest` 的单元测试覆盖。
     * 绝对门限是否偏松（0.35 是否该提高）由评估集在负样本上量化决定。
     */
    @Test
    void unanswerableQuestionIsNotFabricated() {
        String session = "it-refuse-" + UUID.randomUUID();

        AiQaService.ChatResponse resp = aiQaService.chat("学校有没有高尔夫球场", session, "127.0.0.1", null);

        String answer = resp.getAnswer() == null ? "" : resp.getAnswer();
        boolean refusedAtGate = Boolean.TRUE.equals(resp.getIsUnknown());
        boolean declaredNotCovered = answer.contains("未收录") || answer.contains("暂未") || answer.contains("没有找到");

        assertTrue(refusedAtGate || declaredNotCovered,
                "无覆盖的问题必须要么门限拒答，要么明确声明'未收录'，绝不能编造。实际答案：" + answer);

        // 若未走门限拒答，则是 LLM 给出的诚实声明 → 一定调用过 LLM
        Map<String, Object> log = jdbc.queryForMap(
                "SELECT gate_mode, top_score, is_unknown, generate_ms, vector_hits, keyword_hits "
                        + "FROM ai_retrieval_log WHERE session_id = ? ORDER BY id DESC LIMIT 1", session);
        if (refusedAtGate) {
            assertEquals(1, ((Number) log.get("is_unknown")).intValue());
            assertNull(log.get("generate_ms"), "门限拒答路径的 generate_ms 必须为 NULL（未调用 LLM 的核查证据）");
        } else {
            assertNotNull(log.get("generate_ms"), "非拒答路径一定调用过 LLM");
        }
        System.out.println("[RagQueryIT] 无覆盖问题：refusedAtGate=" + refusedAtGate
                + " declaredNotCovered=" + declaredNotCovered
                + " top_score=" + log.get("top_score"));
        System.out.println("[RagQueryIT] 答案=" + answer);
    }

    /** 场景三：检索日志记录了 gate_mode 与**原始分**（不是 RRF 分） */
    @Test
    void retrievalLogRecordsGateModeAndRawTopScore() {
        String session = "it-log-" + UUID.randomUUID();

        aiQaService.chat("军训多长时间", session, "127.0.0.1", null);

        Map<String, Object> log = jdbc.queryForMap(
                "SELECT gate_mode, top_score, embedding_ms, retrieval_ms, generate_ms "
                        + "FROM ai_retrieval_log WHERE session_id = ? ORDER BY id DESC LIMIT 1", session);

        String gateMode = (String) log.get("gate_mode");
        assertTrue("vector".equals(gateMode) || "keyword".equals(gateMode),
                "gate_mode 应为 vector 或 keyword，实际=" + gateMode);

        BigDecimal topScore = (BigDecimal) log.get("top_score");
        assertNotNull(topScore);
        // 关键：top_score 是原始分，量级必然远大于 RRF 分（约 0.016）
        assertTrue(topScore.doubleValue() > 0.1,
                "top_score 应为原始分（>0.1），若约 0.016 说明误把 RRF 分写进了门限字段：" + topScore);

        assertNotNull(log.get("embedding_ms"), "应记录向量化耗时");
        assertNotNull(log.get("retrieval_ms"), "应记录检索耗时");
        System.out.println("[RagQueryIT] 日志 gate_mode=" + gateMode + " top_score=" + topScore
                + " embedding_ms=" + log.get("embedding_ms")
                + " retrieval_ms=" + log.get("retrieval_ms")
                + " generate_ms=" + log.get("generate_ms"));
    }

    /** 场景四：retrieveContext 已委托给 HybridRetriever（DeepSeek 的 RAG 模式据此受益） */
    @Test
    void retrieveContextDelegationWorks() {
        String context = aiQaService.retrieveContext("宿舍有空调吗", 3);

        assertNotNull(context);
        assertFalse(context.isBlank(), "应检索到材料（kb_chunk 已有 173 条向量化数据）");
        assertTrue(context.contains("空调"), "材料应包含与问题相关的内容，实际：" + context);
        assertTrue(context.contains("【参考材料1｜来源："), "材料应带来源元信息：" + context);
    }
}
