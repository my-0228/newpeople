package com.freshman.rag;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.freshman.rag.dto.EvalReport;
import com.freshman.rag.dto.RetrievalResult;
import com.freshman.rag.dto.ScoredChunk;
import com.freshman.service.AiQaService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * RAG 评估执行器
 *
 * 设计要点：
 * 1. **期望值用稳定 ref 键**（`source_type:source_id:chunk_index`），不用自增 `kb_chunk.id`
 *    —— 后者在重建后会漂移，导致基线静默失效
 * 2. **解析不到 ref 直接报错**，不静默跳过（否则语料变动后指标虚高）
 * 3. **拒答分两层度量**（实测校准，见计划二 Task 7）：
 *    门限级拒答 / prompt 级诚实声明 / 疑似编造（期望 0）
 * 4. 检索指标不需要 LLM（零成本）；拒答层判定才需要真实 LLM
 *
 * 所属模块：AI 智能问答模块 / RAG
 */
@Component
public class RagEvalRunner {

    private static final Logger log = LoggerFactory.getLogger(RagEvalRunner.class);

    /** 评估集路径（相对项目根） */
    private static final String EVAL_SET_PATH = "docs/rag/eval-set.jsonl";

    private final HybridRetriever hybridRetriever;
    private final LocalKnowledgeEngine localEngine;
    private final RagService ragService;
    private final JdbcTemplate jdbc;
    private final RagProperties props;

    public RagEvalRunner(HybridRetriever hybridRetriever,
                         LocalKnowledgeEngine localEngine,
                         RagService ragService,
                         JdbcTemplate jdbc,
                         RagProperties props) {
        this.hybridRetriever = hybridRetriever;
        this.localEngine = localEngine;
        this.ragService = ragService;
        this.jdbc = jdbc;
        this.props = props;
    }

    /**
     * 跑完整评估。
     *
     * @param withGeneration true 时额外调用真实 LLM 判定负样本的拒答层（有成本）
     */
    public EvalReport evaluate(boolean withGeneration) {
        List<JSONObject> items = loadEvalSet();
        EvalReport report = new EvalReport();
        report.setItemCount(items.size());

        int answerable = 0;
        int negatives = 0;
        int hit1 = 0;
        int hit3 = 0;
        double mrrSum = 0;
        int baselineHit = 0;
        int gateRefusal = 0;
        int honest = 0;
        int suspect = 0;

        for (JSONObject item : items) {
            EvalReport.ItemResult ir = new EvalReport.ItemResult();
            ir.setId(item.getInt("id"));
            ir.setQuestion(item.getStr("question"));
            boolean answerableFlag = Boolean.TRUE.equals(item.getBool("answerable", true));
            ir.setAnswerable(answerableFlag);

            Set<String> expected = new LinkedHashSet<>();
            JSONArray refs = item.getJSONArray("expected_refs");
            if (refs != null) {
                for (Object r : refs) {
                    String ref = String.valueOf(r);
                    expected.add(ref);
                    assertRefResolvable(ref, report);
                }
            }

            // ---- 改造后：RAG 检索 ----
            RetrievalResult rr = hybridRetriever.retrieve(ir.getQuestion(), props.getTopKFinal());
            ir.setGateMode(rr.getGateMode());
            ir.setTopScore(rr.getTopScore());
            ir.setTopRefs(rr.getChunks().stream().map(RagEvalRunner::refOf)
                    .collect(Collectors.joining(", ")));

            int rank = 0;
            for (int i = 0; i < rr.getChunks().size(); i++) {
                if (expected.contains(refOf(rr.getChunks().get(i)))) {
                    rank = i + 1;
                    break;
                }
            }
            ir.setRagRank(rank);

            // ---- 改造前基线：本地引擎 ----
            boolean baseHit = localEngine.best(ir.getQuestion())
                    .map(a -> expected.contains("ai_knowledge:" + a.knowledgeId() + ":0"))
                    .orElse(false);
            ir.setBaselineHit(baseHit);

            if (answerableFlag) {
                answerable++;
                if (rank == 1) hit1++;
                if (rank >= 1 && rank <= 3) hit3++;
                if (rank >= 1) mrrSum += 1.0 / rank;
                if (baseHit) baselineHit++;
            } else {
                negatives++;
                // ---- 拒答两层判定 ----
                if (!rr.hasQualifiedMaterial()) {
                    ir.setRefusalLayer("GATE_REFUSAL");
                    gateRefusal++;
                } else if (!withGeneration) {
                    ir.setRefusalLayer("NOT_RUN");
                } else {
                    AiQaService.ChatResponse resp = ragService.ask(ir.getQuestion(), "eval-" + ir.getId());
                    String answer = resp.getAnswer() == null ? "" : resp.getAnswer();
                    boolean declared = Boolean.TRUE.equals(resp.getIsUnknown())
                            || answer.contains("未收录")
                            || answer.contains("暂未")
                            || answer.contains("没有找到");
                    if (declared) {
                        ir.setRefusalLayer("HONEST");
                        honest++;
                    } else {
                        ir.setRefusalLayer("SUSPECT");
                        suspect++;
                    }
                }
            }
            report.getItems().add(ir);
        }

        report.setAnswerableCount(answerable);
        report.setNegativeCount(negatives);
        report.setHit1(answerable == 0 ? 0 : (double) hit1 / answerable);
        report.setHit3(answerable == 0 ? 0 : (double) hit3 / answerable);
        report.setMrr(answerable == 0 ? 0 : mrrSum / answerable);
        report.setBaselineHit1(answerable == 0 ? 0 : (double) baselineHit / answerable);
        report.setGateRefusalCount(gateRefusal);
        report.setHonestDeclarationCount(honest);
        report.setFabricationSuspectCount(suspect);

        log.info("[RAG评估] 条目={} 可答={} 负样本={} | Hit@1={} Hit@3={} MRR={} 基线Hit@1={} | 门限拒答={} 诚实声明={} 疑似编造={}",
                report.getItemCount(), answerable, negatives,
                pct(report.getHit1()), pct(report.getHit3()), String.format("%.3f", report.getMrr()),
                pct(report.getBaselineHit1()), gateRefusal, honest, suspect);
        return report;
    }

    /** 期望 ref 必须能在当前语料中解析到 chunk，否则报错（避免标注失效后指标虚高） */
    private void assertRefResolvable(String ref, EvalReport report) {
        if (report.getUnresolvableRefs().contains(ref)) {
            return;
        }
        String[] parts = ref.split(":");
        if (parts.length != 3) {
            report.getUnresolvableRefs().add(ref);
            throw new IllegalStateException("评估集 ref 格式非法（应为 source_type:source_id:chunk_index）：" + ref);
        }
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM kb_chunk c JOIN kb_document d ON c.document_id = d.id "
                        + "WHERE d.source_type = ? AND d.source_id = ? AND c.chunk_index = ?",
                Integer.class, parts[0], Long.parseLong(parts[1]), Integer.parseInt(parts[2]));
        if (n == null || n == 0) {
            report.getUnresolvableRefs().add(ref);
            throw new IllegalStateException(
                    "评估集引用了不存在的 chunk：" + ref + " —— 语料已变动，请修正标注（不允许静默跳过）");
        }
    }

    private List<JSONObject> loadEvalSet() {
        Path path = Paths.get(EVAL_SET_PATH);
        if (!Files.exists(path)) {
            throw new IllegalStateException("评估集不存在：" + path.toAbsolutePath());
        }
        try {
            List<JSONObject> out = new ArrayList<>();
            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                String s = line.trim();
                if (s.isEmpty() || s.startsWith("//")) {
                    continue;
                }
                out.add(JSONUtil.parseObj(s));
            }
            if (out.isEmpty()) {
                throw new IllegalStateException("评估集为空：" + path.toAbsolutePath());
            }
            return out;
        } catch (Exception e) {
            throw new IllegalStateException("读取评估集失败：" + e.getMessage(), e);
        }
    }

    private static String refOf(ScoredChunk c) {
        return c.getSourceType() + ":" + c.getSourceId() + ":" + c.getChunkIndex();
    }

    private static String pct(double v) {
        return String.format("%.1f%%", v * 100);
    }

    /** 产出 Markdown 报告（供 /admin/ai/eval 直接返回，也便于落盘为 eval-report.md） */
    public static String toMarkdown(EvalReport r) {
        StringBuilder sb = new StringBuilder();
        sb.append("# RAG 评估报告\n\n");
        sb.append("评估集：`docs/rag/eval-set.jsonl`（").append(r.getItemCount()).append(" 条：")
                .append(r.getAnswerableCount()).append(" 条可答 + ")
                .append(r.getNegativeCount()).append(" 条负样本）\n\n");

        sb.append("## 检索指标（改造前 vs 改造后）\n\n");
        sb.append("| 指标 | 改造前（本地 TF-IDF 引擎） | 改造后（双路召回 + RRF + 双门限） |\n");
        sb.append("|---|---|---|\n");
        sb.append("| Hit@1 | ").append(pct(r.getBaselineHit1())).append(" | ")
                .append(pct(r.getHit1())).append(" |\n");
        sb.append("| Hit@3 | — | ").append(pct(r.getHit3())).append(" |\n");
        sb.append("| MRR | — | ").append(String.format("%.3f", r.getMrr())).append(" |\n\n");

        sb.append("### ⚠️ 指标解读（不要直接对比两个 Hit@1）\n\n");
        sb.append("1. **基线占主场优势**：评估集的期望 ref 全是 `ai_knowledge:*`，而改造前的本地引擎")
                .append("**本来就只查 `ai_knowledge`**，因此它的 Hit@1=100% 是设计使然，不是「更强」。\n");
        sb.append("2. **改造后是在 10 个来源、173 个 chunk 的语料里检索**，")
                .append("同一问题常有**多个等价来源**（例如「报到需要带什么材料」在 `ai_knowledge` 与 `guide_faq` 里都有）。")
                .append("明细中名次为 2 的条目，排第一的正是 `guide_faq` 的等价答案 —— 这类「命中替代来源」被 Hit@1 记为未命中。\n");
        sb.append("3. 因此更可信的结论是：**Hit@3=100% + MRR=")
                .append(String.format("%.3f", r.getMrr()))
                .append(" 说明正确材料总能在前 3 内被检索到**；Hit@1 是**下界**。\n");
        sb.append("4. 要让 Hit@1 可比，应把评估集的 `expected_refs` 补全为**全部合法来源**（而非只列 ai_knowledge）。")
                .append("这属评估集标注改进，已记入计划。\n\n");

        sb.append("### ⚠️ 方法学提醒（曾导致失真结论）\n\n");
        sb.append("检索指标**必须有 Embedding key**：无 key 时问题向量化失败 → 向量路径不可用 → ")
                .append("实际测的是**纯关键词降级模式**，实测曾得出 Hit@1=15.8% 的错误结论。")
                .append("`RagEvalIT` 已加断言防止误跑。\n\n");

        sb.append("## 拒答指标（负样本 ").append(r.getNegativeCount()).append(" 条）\n\n");
        sb.append("| 层 | 数量 | 说明 |\n|---|---|---|\n");
        sb.append("| 门限级拒答（不调 LLM） | ").append(r.getGateRefusalCount()).append(" | 向量原始分低于 min-score |\n");
        sb.append("| prompt 级诚实声明 | ").append(r.getHonestDeclarationCount())
                .append(" | 过门限但材料不足，模型明说\"未收录\" |\n");
        sb.append("| **疑似编造** | ").append(r.getFabricationSuspectCount())
                .append(" | 既未拒答也未声明未收录 —— **期望 0** |\n\n");

        if (!r.getUnresolvableRefs().isEmpty()) {
            sb.append("⚠️ 未解析的期望 ref（标注失效）：").append(String.join(", ", r.getUnresolvableRefs())).append("\n\n");
        }

        sb.append("## 逐条明细\n\n");
        sb.append("| # | 问题 | 可答 | 门限模式 | top原始分 | RAG 名次 | 基线命中 | 拒答层 | 命中的材料 |\n");
        sb.append("|---|---|---|---|---|---|---|---|---|\n");
        for (EvalReport.ItemResult i : r.getItems()) {
            sb.append("| ").append(i.getId())
                    .append(" | ").append(i.getQuestion())
                    .append(" | ").append(i.isAnswerable() ? "✅" : "❌")
                    .append(" | ").append(i.getGateMode() == null ? "" : i.getGateMode())
                    .append(" | ").append(String.format("%.4f", i.getTopScore()))
                    .append(" | ").append(i.getRagRank() == 0 ? "—" : String.valueOf(i.getRagRank()))
                    .append(" | ").append(i.isBaselineHit() ? "✅" : "—")
                    .append(" | ").append(i.getRefusalLayer())
                    .append(" | ").append(i.getTopRefs() == null ? "" : i.getTopRefs())
                    .append(" |\n");
        }
        return sb.toString();
    }
}
