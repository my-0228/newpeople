package com.freshman.it;

import com.freshman.rag.RagEvalRunner;
import com.freshman.rag.RagProperties;
import com.freshman.rag.dto.EvalReport;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RAG 评估集执行 + 报告落盘。
 *
 * 分两档：
 *  - `retrievalMetricsProducesReport()`（it 层，**零成本**）：只算检索指标与基线对比，
 *    负样本的拒答层标为 NOT_RUN。产出 `docs/rag/eval-report.md` 的基础表。
 *  - `fullMetricsIncludeRefusalLayers()`（`@Tag("eval")`，**需真实 LLM**）：额外判定
 *    负样本落入"门限拒答 / 诚实声明 / 疑似编造"哪一层，重写报告。
 *
 * 运行：
 *   mvn test -Dtest=RagEvalIT -Dtest.excludedGroups=eval                      # 零成本档
 *   mvn test -Dtest=RagEvalIT#fullMetricsIncludeRefusalLayers -Dtest.excludedGroups=   # 全量档
 */
@Tag("it")
@SpringBootTest
class RagEvalIT {

    private static final Path REPORT = Paths.get("docs/rag/eval-report.md");

    @Autowired
    private RagEvalRunner runner;

    @Autowired
    private com.freshman.rag.KeywordRetriever keywordRetriever;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private RagProperties ragProperties;

    @BeforeEach
    void requireIndexedCorpusAndApiKey() {
        Integer withVector = jdbc.queryForObject(
                "SELECT COUNT(*) FROM kb_chunk WHERE status = 1 AND embedding IS NOT NULL", Integer.class);
        Assumptions.assumeTrue(withVector != null && withVector > 0,
                "kb_chunk 中没有已向量化的 chunk，请先跑 RagIndexIT（需要 DASHSCOPE_API_KEY）");

        // ⚠️ 检索指标**也必须**有 embedding key：
        // 否则问题向量化失败 → 向量路径不可用 → 评测的其实是"纯关键词降级模式"，
        // 得到的 Hit@1 会严重失真（实测曾因此得到 15.8% 的错误结论）。
        String key = ragProperties.getEmbedding().getApiKey();
        Assumptions.assumeTrue(key != null && !key.isBlank(),
                "未设置 DASHSCOPE_API_KEY：检索指标需要问题向量化，否则测的是降级模式而非 RAG");
    }

    @Test
    void retrievalMetricsProducesReport() throws Exception {
        EvalReport r = runner.evaluate(false);

        // 先确认测的是向量路径：否则下面断言与写报告都在衡量"降级模式"
        assertVectorPathEngaged(r);

        assertEquals(24, r.getItemCount(), "评估集应为 24 条");
        assertEquals(19, r.getAnswerableCount());
        assertEquals(5, r.getNegativeCount());
        assertTrue(r.getUnresolvableRefs().isEmpty(),
                "所有期望 ref 都必须能解析到 chunk（否则标注已失效）：" + r.getUnresolvableRefs());

        // 检索质量的基本不变量
        assertTrue(r.getHit1() > 0, "改造后 Hit@1 不应为 0");
        assertTrue(r.getHit3() >= r.getHit1(), "Hit@3 应 >= Hit@1");
        assertTrue(r.getMrr() > 0 && r.getMrr() <= 1.0, "MRR 应在 (0,1]");

        writeReport(r);
        System.out.printf("[RAG评估] Hit@1=%.1f%%（基线 %.1f%%） Hit@3=%.1f%% MRR=%.3f%n",
                r.getHit1() * 100, r.getBaselineHit1() * 100, r.getHit3() * 100, r.getMrr());
    }

    @Tag("eval")
    @Test
    void fullMetricsIncludeRefusalLayers() throws Exception {
        String key = ragProperties.getEmbedding().getApiKey();
        Assumptions.assumeTrue(key != null && !key.isBlank(), "未设置 DASHSCOPE_API_KEY");

        EvalReport r = runner.evaluate(true);

        assertVectorPathEngaged(r);

        assertEquals(5, r.getNegativeCount());
        assertEquals(0, r.getFabricationSuspectCount(),
                "负样本上不得出现既未拒答又未声明未收录的情况（疑似编造）："
                        + r.getItems().stream()
                        .filter(i -> !i.isAnswerable() && "SUSPECT".equals(i.getRefusalLayer()))
                        .map(EvalReport.ItemResult::getQuestion).toList());
        assertEquals(5, r.getGateRefusalCount() + r.getHonestDeclarationCount(),
                "5 条负样本应全部落入两层拒答之一");

        writeReport(r);
        System.out.printf("[RAG评估-全量] 门限拒答=%d 诚实声明=%d 疑似编造=%d%n",
                r.getGateRefusalCount(), r.getHonestDeclarationCount(), r.getFabricationSuspectCount());
    }

    /**
     * 离线（纯关键词）模式的可用性保护。
     *
     * 背景：评估曾出现 Hit@1=15.8% 的失真结果，根因是没设 key → 向量路径不可用 →
     * 退到关键词路径 → 而**关键词门限把它自己几乎全拦掉了**。若属实，意味着
     * "断网/无 key 时系统仍可用"这个 G8 目标实际上是坏的。
     *
     * 本用例断言：语料里**逐字存在**的问题，其关键词得分必须能过 keyword-min-score。
     * 如果这条失败，说明 `keyword-min-score` 相对本语料的 TF-IDF 余弦量级标定错误，
     * 需要用实测数据下调（而不是凭感觉）。
     */
    @Test
    void keywordOnlyModeCanStillAnswerVerbatimQuestions() {
        String question = "宿舍有空调吗";
        var hits = keywordRetriever.search(question, 5);

        assertFalse(hits.isEmpty(), "关键词路径至少应召回一些候选（否则离线模式完全不可用）");
        double top = hits.get(0).getKeywordScore();
        double gate = ragProperties.getKeywordMinScore();
        System.out.printf("[RAG评估] 关键词路径 top score=%.4f，门限 keyword-min-score=%.2f%n", top, gate);
        assertTrue(top >= gate,
                String.format("语料里逐字存在的问题，关键词得分 %.4f 却低于门限 %.2f —— "
                        + "离线模式会把能答的问题也拒掉，必须按实测量级重新标定 keyword-min-score",
                        top, gate));
    }

    private void writeReport(EvalReport r) throws Exception {
        Files.createDirectories(REPORT.getParent());
        Files.write(REPORT, RagEvalRunner.toMarkdown(r).getBytes(StandardCharsets.UTF_8));
        System.out.println("[RAG评估] 报告已写入 " + REPORT.toAbsolutePath());
    }

    /**
     * 只有**真正跑在向量模式**时才允许继续断言并覆盖报告。
     *
     * 背景（真实事故）：本测试原来只检查"embedding key 非空"。而"配了 key" ≠ "向量化可用"——
     * 当 yml 里填的是**别的服务商的 key**（例如把 DeepSeek 的 key 填进阿里百炼的 embeddings 端点）时，
     * 问题向量化会失败、检索**静默降级**到关键词模式，于是：
     *   1. 评测的其实是"降级模式"，指标严重失真（实测 Hit@1 由 84.2% 掉到 57.9%，MRR 由 0.921 掉到 0.781）；
     *   2. 失真指标被**无条件覆盖**写入提交进仓库的 `docs/rag/eval-report.md`，伪造了历史结论。
     *
     * 规则（区分"环境不具备"与"配置有问题"）：
     *   - 没配 key            → 跳过（环境本就不具备测向量路径的条件）；
     *   - 配了 key 却没走向量 → **报错**（配置无效或向量链路坏了，必须暴露，不能静默跳过）；
     *   - 全部走向量          → 正常继续。
     */
    private void assertVectorPathEngaged(EvalReport r) {
        long answerable = r.getItems().stream().filter(EvalReport.ItemResult::isAnswerable).count();
        long vectorRan = r.getItems().stream()
                .filter(EvalReport.ItemResult::isAnswerable)
                .filter(i -> "vector".equals(i.getGateMode()))
                .count();

        if (answerable > 0 && vectorRan == answerable) {
            return;
        }

        String key = ragProperties.getEmbedding().getApiKey();
        boolean keyConfigured = key != null && !key.isBlank();

        if (keyConfigured) {
            // 配了 key 却没生效：这是必须修复的配置/链路问题，不能静默跳过
            fail(String.format(
                    "已配置 embedding key，但 %d/%d 条可答问题走的是**关键词降级模式** —— "
                            + "说明问题向量化失败（key 对该服务无效？端点写错？）。"
                            + "本次指标只反映降级模式，已拒绝覆盖 %s。"
                            + "请检查 app.ai.rag.embedding.api-url / api-key / model 三者是否匹配同一服务商。",
                    answerable - vectorRan, answerable, REPORT));
        }
        // 未配置 key：环境不具备条件，跳过（同时避免覆盖报告）
        Assumptions.abort("未配置 embedding key，向量路径不可用；跳过评测以避免用降级指标覆盖报告");
    }
}
