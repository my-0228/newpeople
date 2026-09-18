package com.freshman.agent.tool;

import com.freshman.agent.AgentTool;
import com.freshman.agent.ToolContext;
import com.freshman.agent.ToolResult;
import com.freshman.rag.HybridRetriever;
import com.freshman.rag.dto.RetrievalResult;
import com.freshman.rag.dto.ScoredChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具：search_knowledge —— 检索本地知识库
 *
 * **这是子系统 A（RAG）与子系统 B（Agent）的接缝**：Agent 不重新造一套知识访问，
 * 而是把 RAG 的 `HybridRetriever`（向量 + 关键词双路召回 + RRF + 双门限）注册成一个工具。
 * 这也正是"必须先做 RAG"的原因。
 *
 * 两个刻意的设计：
 *  1. **检索无覆盖时 success=true**，content 里明确写"知识库未覆盖" ——
 *     若返回 success=false，模型会把它当成"系统故障"而不是"知识库没有"，
 *     从而可能改用自身记忆编造。
 *  2. **只投影 title/urlPath/snippet/sourceType/sourceId**，绝不把 `rrfScore` 给模型
 *     （RRF 分只用于排序，量级约 0.016，当成"相似度"会误导模型判断材料可信度）。
 *
 * 所属模块：DeepSeek 问答 / Agent
 */
@Component
public class SearchKnowledgeTool implements AgentTool {

    private static final Logger log = LoggerFactory.getLogger(SearchKnowledgeTool.class);

    private static final int DEFAULT_TOP_K = 3;
    private static final int MIN_TOP_K = 1;
    private static final int MAX_TOP_K = 10;

    private final HybridRetriever hybridRetriever;

    public SearchKnowledgeTool(HybridRetriever hybridRetriever) {
        this.hybridRetriever = hybridRetriever;
    }

    @Override
    public String name() {
        return "search_knowledge";
    }

    @Override
    public String description() {
        return "检索学校本地知识库（含迎新指南、报到流程、军训、宿舍、缴费、奖助学金、社团、"
                + "校园生活、专业介绍、校园建筑、新闻公告等），返回带来源的参考材料。"
                + "当用户问学校相关的具体事实（流程、规定、费用、地点、设施等）时应当先调用本工具，"
                + "并只依据返回的材料回答。";
    }

    @Override
    public Map<String, Object> parameters() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("query", Map.of(
                "type", "string",
                "description", "检索用的自然语言问题，尽量保留用户原话中的关键信息"));
        props.put("top_k", Map.of(
                "type", "integer",
                "description", "返回材料条数，1-10，默认 3",
                "minimum", MIN_TOP_K,
                "maximum", MAX_TOP_K));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", List.of("query"));
        return schema;
    }

    @Override
    public ToolResult execute(Map<String, Object> args, ToolContext ctx) {
        Object rawQuery = args == null ? null : args.get("query");
        if (rawQuery == null || String.valueOf(rawQuery).isBlank()) {
            return ToolResult.fail("缺少参数 query");
        }
        String query = String.valueOf(rawQuery).trim();
        int topK = clampTopK(args == null ? null : args.get("top_k"));

        RetrievalResult rr;
        try {
            rr = hybridRetriever.retrieve(query, topK);
        } catch (Exception e) {
            log.warn("[Agent] search_knowledge 检索异常：{}", e.getMessage());
            return ToolResult.fail("检索失败：" + e.getMessage());
        }

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("hasMaterial", rr.hasQualifiedMaterial());
        meta.put("gateMode", rr.getGateMode());
        meta.put("count", rr.getChunks().size());

        if (!rr.hasQualifiedMaterial()) {
            // 刻意 success=true：让模型区分"知识库没有"与"系统故障"
            return ToolResult.ok(
                    "知识库未覆盖该问题（未检索到合格材料）。"
                            + "请如实告知用户你无法从学校资料中确认，并建议其换一种问法、"
                            + "或咨询辅导员/招生办，**不要凭自己的记忆编造学校具体信息**。", meta);
        }

        List<Map<String, Object>> materials = new ArrayList<>(rr.getChunks().size());
        StringBuilder sb = new StringBuilder();
        sb.append("命中 ").append(rr.getChunks().size()).append(" 条参考材料：\n");
        int i = 0;
        for (ScoredChunk c : rr.getChunks()) {
            i++;
            Double raw = HybridRetriever.GATE_VECTOR.equals(rr.getGateMode())
                    ? c.getVectorCosine() : c.getKeywordScore();
            sb.append(i).append(". 【").append(c.getTitle() == null ? c.getSourceType() : c.getTitle())
                    .append("】相似度 ").append(raw == null ? "—" : String.format("%.2f", raw))
                    .append(" 来源路径 ").append(c.getUrlPath() == null ? "（无独立页面）" : c.getUrlPath())
                    .append("\n   ").append(c.getSnippet() == null ? "" : c.getSnippet().replace("\n", " "))
                    .append("\n");

            // 只投影引用与展示所需字段 —— 不含 rrfScore
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("index", i);
            m.put("title", c.getTitle());
            m.put("url_path", c.getUrlPath());
            m.put("snippet", c.getSnippet());
            m.put("source_type", c.getSourceType());
            m.put("source_id", c.getSourceId());
            materials.add(m);
        }
        sb.append("\n请只依据以上材料回答；材料不足时如实说明，不要编造。");
        meta.put("materials", materials);
        return ToolResult.ok(sb.toString(), meta);
    }

    /** topK 钳制到 [1,10]：模型可能给出 0、负数或超大值，不能让它影响检索层 */
    static int clampTopK(Object raw) {
        if (raw == null) {
            return DEFAULT_TOP_K;
        }
        int v;
        if (raw instanceof Number n) {
            v = n.intValue();
        } else {
            try {
                v = (int) Double.parseDouble(String.valueOf(raw).trim());
            } catch (Exception e) {
                return DEFAULT_TOP_K;
            }
        }
        return Math.max(MIN_TOP_K, Math.min(MAX_TOP_K, v));
    }
}
