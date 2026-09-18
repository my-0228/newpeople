package com.freshman.rag;

import com.freshman.entity.AiRetrievalLog;
import com.freshman.mapper.AiRetrievalLogMapper;
import com.freshman.rag.dto.Citation;
import com.freshman.rag.dto.RetrievalResult;
import com.freshman.rag.dto.ScoredChunk;
import com.freshman.rag.llm.LlmClient;
import com.freshman.rag.llm.LlmResult;
import com.freshman.service.AiQaService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * RAG 编排服务：检索 → 门限 → 生成 → 引用校验 → 拒答/降级 → 落检索日志
 *
 * 职责边界（钉死，避免与 AiQaServiceImpl 混淆）：
 *  - 本类**只**写 `ai_retrieval_log`；**不写** `ai_chat_history`
 *    （历史落库仍由 `AiQaServiceImpl.chat()` 负责，因为它持有 userId/ip）
 *  - 因此本类入参只需 question/sessionId，不需要 userId/ip
 *
 * 三条关键行为：
 *  1. **拒答不调用 LLM**：`hasQualifiedMaterial=false` 时直接返回固定话术，
 *     既省钱又零幻觉；此时 `ai_retrieval_log.generate_ms` 记 NULL
 *  2. **幻觉引用剔除**：答案引用了不存在的编号 → 剔除该引用、记 warn、标 `degraded=true`
 *  3. **降级不破坏可用性**：LLM 失败时退回 `LocalAnswerProvider`（= 改造前的本地匹配），
 *     标 `degraded=true`
 *
 * 所属模块：AI 智能问答模块 / RAG
 * @author AI Module Team
 * @version 1.0
 */
@Service
public class RagService {

    private static final Logger log = LoggerFactory.getLogger(RagService.class);

    /** 答案中的引用标记，如 [1]、[12] */
    private static final Pattern CITATION = Pattern.compile("\\[(\\d{1,2})]");

    /** 材料块上限（snippet 截断长度由 ScoredChunk 决定） */
    private static final int MAX_SNIPPET_PER_MATERIAL = 400;

    private static final String SYSTEM_PROMPT =
            "你是东北石油大学智慧迎新系统的迎新助手（哈基油油子），专门为大一新生解答入学相关问题。\n\n"
                    + "回答要求：\n"
                    + "1. 只依据下面提供的参考材料回答，不要使用材料之外的学校具体信息\n"
                    + "2. 每个论断后面必须标注引用编号，如 [1]、[2]\n"
                    + "3. 如果参考材料不足以回答，必须明确说明\"知识库暂未收录该问题\"，"
                    + "并给出建议咨询渠道，禁止编造\n"
                    + "4. 友好、简洁、用中文，可适当使用 emoji\n\n"
                    + "参考材料：\n";

    private static final String REFUSAL_ANSWER =
            "😅 知识库暂未收录该问题。\n\n"
                    + "建议你换个说法再问一次，或通过以下渠道咨询：\n"
                    + "① 拨打学校招生办电话\n"
                    + "② 咨询所在学院的辅导员\n"
                    + "③ 查看「迎新指南」页面的相关内容";

    private final HybridRetriever hybridRetriever;
    private final LlmClient llmClient;
    private final LocalAnswerProvider localAnswerProvider;
    private final AiRetrievalLogMapper retrievalLogMapper;
    private final RagProperties props;

    public RagService(HybridRetriever hybridRetriever,
                      LlmClient llmClient,
                      LocalAnswerProvider localAnswerProvider,
                      AiRetrievalLogMapper retrievalLogMapper,
                      RagProperties props) {
        this.hybridRetriever = hybridRetriever;
        this.llmClient = llmClient;
        this.localAnswerProvider = localAnswerProvider;
        this.retrievalLogMapper = retrievalLogMapper;
        this.props = props;
    }

    /**
     * 问答主流程。
     *
     * @param question  用户问题（非空）
     * @param sessionId 会话标识（仅用于检索日志）
     */
    public AiQaService.ChatResponse ask(String question, String sessionId) {
        long start = System.currentTimeMillis();

        AiQaService.ChatResponse resp = new AiQaService.ChatResponse();
        resp.setQuestion(question);

        // ---------- ① 检索 + 门限 ----------
        long tRetrieval = System.currentTimeMillis();
        RetrievalResult rr = hybridRetriever.retrieve(question, props.getTopKFinal());
        long retrievalMs = System.currentTimeMillis() - tRetrieval;

        // ---------- ② 无合格材料 → 拒答（**不调用 LLM**）----------
        if (!rr.hasQualifiedMaterial()) {
            resp.setAnswer(REFUSAL_ANSWER);
            resp.setConfidence(0.0);
            resp.setCategory("未知问题");
            resp.setIsUnknown(true);
            resp.setCitations(List.of());
            resp.setDegraded(false);
            resp.setCostMs(System.currentTimeMillis() - start);
            // generate_ms 记 NULL —— 这是"拒答未调用 LLM"的可核查证据
            writeLog(sessionId, question, rr, null, List.of(), true, false, retrievalMs, null);
            log.info("[RAG] 拒答（gateMode={}, topScore={}）", rr.getGateMode(), rr.getTopScore());
            return resp;
        }

        // ---------- ③ 组装 prompt（材料带元信息）----------
        List<ScoredChunk> materials = rr.getChunks();
        String systemPrompt = SYSTEM_PROMPT + buildMaterialBlock(materials, rr.getGateMode());

        // ---------- ④ 生成 ----------
        LlmResult llm = null;
        if (llmClient.isConfigured()) {
            llm = llmClient.complete(systemPrompt, question);
        }
        Long generateMs = llm == null ? null : llm.costMs();
        boolean degraded = false;

        if (llm != null && llm.success()) {
            // ---------- ⑤ 引用解析 + 幻觉引用剔除 ----------
            ParseResult parsed = parseCitations(llm.content(), materials);
            if (parsed.hallucinated()) {
                degraded = true;
                log.warn("[RAG] 答案引用了不存在的材料编号，已剔除并标记降级：{}", parsed.hallucinatedIndexes());
            }
            resp.setAnswer(llm.content());
            resp.setCitations(parsed.citations());
            resp.setCategory(materials.get(0).getTitle());
        } else {
            // ---------- ⑥ 降级：退回本地引擎（= 改造前行为）----------
            degraded = true;
            String reason = llm == null ? "LLM 未配置" : llm.error();
            Optional<LocalAnswerProvider.LocalAnswer> local = localAnswerProvider.best(question);
            if (local.isPresent()) {
                LocalAnswerProvider.LocalAnswer a = local.get();
                resp.setAnswer("（离线降级回答）\n" + a.answer());
                resp.setCategory(a.category());
            } else {
                resp.setAnswer("（离线降级回答）\n" + REFUSAL_ANSWER);
                resp.setCategory("未知问题");
                resp.setIsUnknown(true);
            }
            resp.setCitations(List.of());
            log.warn("[RAG] 生成失败，已降级到本地引擎：{}", reason);
        }

        resp.setConfidence(round(rr.getTopScore()));
        if (resp.getIsUnknown() == null) {
            resp.setIsUnknown(false);
        }
        resp.setDegraded(degraded);
        resp.setCostMs(System.currentTimeMillis() - start);

        writeLog(sessionId, question, rr,
                materials.isEmpty() ? null : materials.get(0),
                resp.getCitations(), Boolean.TRUE.equals(resp.getIsUnknown()), degraded,
                retrievalMs, generateMs);
        return resp;
    }

    // ==================== prompt 与引用 ====================

    /**
     * 材料块：带来源与**当前 gateMode 的原始分**（绝不展示 RRF 分）。
     * 例：【材料1｜来源：迎新指南-报到流程｜相似度：0.62】
     */
    String buildMaterialBlock(List<ScoredChunk> materials, String gateMode) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < materials.size(); i++) {
            ScoredChunk m = materials.get(i);
            Double raw = HybridRetriever.GATE_VECTOR.equals(gateMode) ? m.getVectorCosine() : m.getKeywordScore();
            String source = m.getTitle() == null ? m.getSourceType() : m.getTitle();
            sb.append("【材料").append(i + 1)
                    .append("｜来源：").append(source)
                    .append("｜相似度：").append(raw == null ? "-" : String.format("%.2f", raw))
                    .append("】\n");
            String body = m.getContent() == null ? m.getSnippet() : m.getContent();
            if (body != null && body.length() > MAX_SNIPPET_PER_MATERIAL) {
                body = body.substring(0, MAX_SNIPPET_PER_MATERIAL);
            }
            sb.append(body == null ? "" : body).append("\n\n");
        }
        return sb.toString();
    }

    /** 引用解析结果 */
    record ParseResult(List<Citation> citations, boolean hallucinated, Set<Integer> hallucinatedIndexes) {}

    /**
     * 解析答案中的 [n]：
     *  - n 在 1..materials.size() 内 → 生成引用
     *  - n 超出范围 → **幻觉引用**，剔除并记录（调用方据此标 degraded）
     */
    ParseResult parseCitations(String answer, List<ScoredChunk> materials) {
        Set<Integer> used = new LinkedHashSet<>();
        Set<Integer> bad = new LinkedHashSet<>();
        Matcher m = CITATION.matcher(answer == null ? "" : answer);
        while (m.find()) {
            int n = Integer.parseInt(m.group(1));
            if (n >= 1 && n <= materials.size()) {
                used.add(n);
            } else {
                bad.add(n);
            }
        }
        List<Citation> citations = new ArrayList<>(used.size());
        for (Integer n : used) {
            ScoredChunk c = materials.get(n - 1);
            Citation cit = new Citation();
            cit.setIndex(n);
            cit.setTitle(c.getTitle());
            cit.setUrlPath(c.getUrlPath());
            cit.setSnippet(c.getSnippet());
            cit.setSourceType(c.getSourceType());
            cit.setSourceId(c.getSourceId());
            cit.setChunkId(c.getChunkId());
            cit.setScore(c.getVectorCosine() != null ? c.getVectorCosine() : c.getKeywordScore());
            citations.add(cit);
        }
        return new ParseResult(citations, !bad.isEmpty(), bad);
    }

    // ==================== 检索日志 ====================

    private void writeLog(String sessionId, String question, RetrievalResult rr,
                          ScoredChunk top, List<Citation> citations,
                          boolean unknown, boolean degraded,
                          long retrievalMs, Long generateMs) {
        try {
            AiRetrievalLog entity = new AiRetrievalLog();
            entity.setSessionId(sessionId);
            entity.setQuestion(question == null ? "" : (question.length() > 500 ? question.substring(0, 500) : question));
            entity.setVectorHits(rr.getVectorHits());
            entity.setKeywordHits(rr.getKeywordHits());
            entity.setGateMode(rr.getGateMode());
            entity.setTopScore(BigDecimal.valueOf(rr.getTopScore()).setScale(4, java.math.RoundingMode.HALF_UP));
            entity.setFinalChunkIds(joinChunkIds(rr.getChunks()));
            entity.setIsUnknown(unknown ? 1 : 0);
            entity.setDegraded(degraded ? 1 : 0);
            entity.setEmbeddingMs((int) rr.getEmbeddingMs());
            entity.setRetrievalMs((int) retrievalMs);
            entity.setGenerateMs(generateMs == null ? null : generateMs.intValue());
            if (entity.getFinalChunkIds() == null && top != null && top.getChunkId() != null) {
                entity.setFinalChunkIds(String.valueOf(top.getChunkId()));
            }
            retrievalLogMapper.insert(entity);
        } catch (Exception e) {
            // 观测失败绝不能影响回答
            log.warn("[RAG] 检索日志写入失败：{}", e.getMessage());
        }
    }

    private static String joinChunkIds(List<ScoredChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (ScoredChunk c : chunks) {
            if (c.getChunkId() == null) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(c.getChunkId());
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    private static Double round(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }
}
