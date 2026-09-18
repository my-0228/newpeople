package com.freshman.rag;

import com.freshman.entity.AiKnowledge;
import com.freshman.mapper.AiKnowledgeMapper;
import com.freshman.rag.tokenizer.ChineseTokenizer;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 本地知识匹配引擎（**从 AiQaServiceImpl 原样抽出，算法一行未改**）
 *
 * 为什么要有它：它是"改造前行为"的定义，也是 RAG 的**降级路径**。
 * 规格原计划让 `KeywordRetriever.fusionScore` 承担这一职责，但 `fusionScore` 依赖
 * `priority` 字段，而 `kb_chunk` 没有该列 —— 用同一段代码（作用于 `ai_knowledge`）
 * 才能天然做到"与改造前逐例一致"。
 *
 * 算法要点（与抽出前一致）：
 *  - 多策略融合评分：Jaccard 0.30 + TF-IDF 余弦 0.50 + 分类加成 0.20 + priority×0.01
 *  - 未知问题阈值 0.25（低于即判为无匹配）
 *  - 同义词扩展后参与匹配；doc 特征词 = 问题 + 关键词 + 同义词 + 答案
 *
 * 所属模块：AI 智能问答模块 / RAG（降级路径）
 * @author AI Module Team
 * @version 1.0
 */
@Component
public class LocalKnowledgeEngine implements LocalAnswerProvider {

    private static final Logger log = LoggerFactory.getLogger(LocalKnowledgeEngine.class);

    /** 未知问题置信度阈值：得分低于此值判定为未知问题 */
    private static final double UNKNOWN_THRESHOLD = 0.25;

    /** Jaccard 相似度权重 */
    private static final double WEIGHT_JACCARD = 0.30;

    /** TF-IDF 余弦相似度权重 */
    private static final double WEIGHT_TFIDF = 0.50;

    /** 分类相关性权重 */
    private static final double WEIGHT_CATEGORY = 0.20;

    /** IDF 平滑系数（拉普拉斯平滑，防止 log(0)） */
    private static final double IDF_SMOOTHING = 1.0;

    /** 分类关键词映射（意图识别） */
    private static final Map<String, String> CATEGORY_KEYWORDS = new LinkedHashMap<>();

    static {
        CATEGORY_KEYWORDS.put("报到流程", "报到,入学,通知书,接站,注册,开学,报到流程,报到材料,体检,团关系");
        CATEGORY_KEYWORDS.put("军训", "军训,军服,迷彩,训练,教官,队列,射击,内务,汇报表演,阅兵");
        CATEGORY_KEYWORDS.put("宿舍", "宿舍,寝室,住宿,房间,空调,独卫,洗澡,水电,门禁,电器");
        CATEGORY_KEYWORDS.put("缴费", "学费,缴费,费用,交费,钱,收费,付款,绿色通道");
        CATEGORY_KEYWORDS.put("奖学金", "奖学金,助学金,贷款,资助,补助,困难认定,勤工俭学");
        CATEGORY_KEYWORDS.put("社团", "社团,协会,俱乐部,组织,招新,学生会,百团大战");
        CATEGORY_KEYWORDS.put("校园生活", "食堂,图书馆,自习,运动,校园卡,快递,交通,校园网,WiFi,网络");
    }

    private final AiKnowledgeMapper knowledgeMapper;
    private final ChineseTokenizer tokenizer;

    /** 全量知识条目（启用状态） */
    private volatile List<AiKnowledge> knowledgeBase = List.of();

    /** 词 → IDF 值 */
    private volatile Map<String, Double> idfMap = Map.of();

    /** 知识条目 id → TF-IDF 向量 */
    private volatile Map<Long, Map<String, Double>> docVectors = Map.of();

    public LocalKnowledgeEngine(AiKnowledgeMapper knowledgeMapper, ChineseTokenizer tokenizer) {
        this.knowledgeMapper = knowledgeMapper;
        this.tokenizer = tokenizer;
    }

    /** 启动时加载知识库并预热向量索引 */
    @PostConstruct
    public void init() {
        try {
            reload();
        } catch (Exception e) {
            // 本地引擎加载失败不应阻断启动（RAG 主路径仍可用）
            log.warn("[本地引擎] 初始化失败，降级路径将不可用：{}", e.getMessage());
        }
    }

    /** 重新加载知识库并重建向量索引；知识库更新后调用 */
    public synchronized void reload() {
        List<AiKnowledge> rows = knowledgeMapper.selectAllEnabled();
        this.knowledgeBase = rows == null ? List.of() : rows;
        buildVectorIndex();
        registerTokenizerTerms();
        log.info("[本地引擎] 已加载知识条目 {} 条，词表 {} 项", knowledgeBase.size(), idfMap.size());
    }

    /**
     * 把知识条目的 keywords / synonyms 注册为分词器的动态词典。
     * 放在引擎里做：它本来就是知识库的持有方，避免调用方重复拼装词表。
     */
    private void registerTokenizerTerms() {
        tokenizer.clearTerms();
        List<String> terms = new ArrayList<>();
        for (AiKnowledge doc : knowledgeBase) {
            if (doc.getKeywords() != null) {
                terms.add(doc.getKeywords());
            }
            if (doc.getSynonyms() != null) {
                terms.add(doc.getSynonyms());
            }
        }
        tokenizer.registerTerms(terms);
    }

    /** 全部知识分类（供 /api/ai/categories） */
    public String[] categories() {
        return CATEGORY_KEYWORDS.keySet().toArray(new String[0]);
    }

    /** 引擎状态统计（供 /api/ai/stats） */
    public Map<String, Object> stats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalKnowledge", knowledgeBase.size());
        stats.put("vocabularySize", idfMap.size());
        stats.put("threshold", UNKNOWN_THRESHOLD);
        stats.put("weights", Map.of(
                "jaccard", WEIGHT_JACCARD,
                "tfidf", WEIGHT_TFIDF,
                "category", WEIGHT_CATEGORY));
        Map<String, Long> categoryCount = knowledgeBase.stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        k -> k.getCategory() != null ? k.getCategory() : "未分类",
                        java.util.stream.Collectors.counting()));
        stats.put("categoryDistribution", categoryCount);
        return stats;
    }

    /** 知识条目数（供状态统计） */
    public int knowledgeSize() {
        return knowledgeBase.size();
    }

    /** 词汇表大小（供状态统计/日志） */
    public int vocabularySize() {
        return idfMap.size();
    }

    @Override
    public Optional<LocalAnswer> best(String question) {
        if (question == null || question.isBlank() || knowledgeBase.isEmpty()) {
            return Optional.empty();
        }
        List<String> questionTokens = tokenizer.expandSynonyms(tokenizer.tokenize(question));
        Map<String, Double> queryVector = computeQueryTfIdf(questionTokens);

        AiKnowledge best = null;
        double bestScore = 0;
        for (AiKnowledge doc : knowledgeBase) {
            double score = computeFusionScore(queryVector, questionTokens, doc);
            if (score > bestScore) {
                bestScore = score;
                best = doc;
            }
        }
        if (best == null || bestScore < UNKNOWN_THRESHOLD) {
            log.debug("[本地引擎] 无合格匹配（最高分 {}）", String.format("%.3f", bestScore));
            return Optional.empty();
        }
        log.debug("[本地引擎] 命中 id={}, score={}", best.getId(), String.format("%.3f", bestScore));
        return Optional.of(new LocalAnswer(best.getId(), best.getAnswer(), best.getCategory(), bestScore));
    }

    // ==================== TF-IDF 向量化 ====================

    private Map<String, Double> computeQueryTfIdf(List<String> tokens) {
        Map<String, Double> tfIdfVector = new HashMap<>();
        if (tokens.isEmpty()) {
            return tfIdfVector;
        }
        int totalTerms = tokens.size();
        Map<String, Integer> termFreq = new HashMap<>();
        for (String token : tokens) {
            termFreq.merge(token, 1, Integer::sum);
        }
        for (Map.Entry<String, Integer> entry : termFreq.entrySet()) {
            String term = entry.getKey();
            double tf = (double) entry.getValue() / totalTerms;
            double idf = idfMap.getOrDefault(term, Math.log(knowledgeBase.size() + IDF_SMOOTHING));
            tfIdfVector.put(term, tf * idf);
        }
        return tfIdfVector;
    }

    // ==================== 多策略融合评分 ====================

    /** 最终得分 = 0.3×Jaccard + 0.5×TF-IDF 余弦 + 0.2×分类加成 + priority×0.01，上限 1.0 */
    private double computeFusionScore(Map<String, Double> queryVector,
                                      List<String> queryTokens,
                                      AiKnowledge doc) {
        double jaccardScore = computeJaccardSimilarity(queryTokens, doc);
        double cosineScore = computeCosineSimilarity(queryVector, doc);
        double categoryBonus = computeCategoryBonus(queryTokens, doc);
        double priorityBonus = doc.getPriority() != null ? doc.getPriority() * 0.01 : 0;

        double fusionScore = WEIGHT_JACCARD * jaccardScore
                + WEIGHT_TFIDF * cosineScore
                + WEIGHT_CATEGORY * categoryBonus
                + priorityBonus;
        return Math.min(fusionScore, 1.0);
    }

    /** Jaccard(A,B) = |A∩B| / |A∪B| */
    private double computeJaccardSimilarity(List<String> queryTokens, AiKnowledge doc) {
        Set<String> docTokens = extractDocTokens(doc);
        if (docTokens.isEmpty() || queryTokens.isEmpty()) {
            return 0;
        }
        Set<String> intersection = new HashSet<>(queryTokens);
        intersection.retainAll(docTokens);
        Set<String> union = new HashSet<>(queryTokens);
        union.addAll(docTokens);
        return union.isEmpty() ? 0 : (double) intersection.size() / union.size();
    }

    private double computeCosineSimilarity(Map<String, Double> queryVector, AiKnowledge doc) {
        Map<String, Double> docVector = docVectors.get(doc.getId());
        if (docVector == null || docVector.isEmpty() || queryVector.isEmpty()) {
            return 0;
        }
        double dotProduct = 0;
        for (Map.Entry<String, Double> entry : queryVector.entrySet()) {
            Double docWeight = docVector.get(entry.getKey());
            if (docWeight != null) {
                dotProduct += entry.getValue() * docWeight;
            }
        }
        double queryNorm = 0;
        for (double v : queryVector.values()) {
            queryNorm += v * v;
        }
        queryNorm = Math.sqrt(queryNorm);
        double docNorm = 0;
        for (double v : docVector.values()) {
            docNorm += v * v;
        }
        docNorm = Math.sqrt(docNorm);
        if (queryNorm == 0 || docNorm == 0) {
            return 0;
        }
        return dotProduct / (queryNorm * docNorm);
    }

    private double computeCategoryBonus(List<String> queryTokens, AiKnowledge doc) {
        String queryCategory = detectCategory(queryTokens);
        if (queryCategory != null && queryCategory.equals(doc.getCategory())) {
            return 1.0;
        }
        if (queryCategory != null && doc.getCategory() != null) {
            return 0.3;
        }
        return 0.0;
    }

    /** 从问题分词中检测意图分类（至少命中 2 个关键词才判定） */
    private String detectCategory(List<String> queryTokens) {
        String queryStr = String.join(" ", queryTokens);
        String bestCategory = null;
        int bestScore = 0;
        for (Map.Entry<String, String> entry : CATEGORY_KEYWORDS.entrySet()) {
            int score = 0;
            for (String kw : entry.getValue().split("[,，]")) {
                if (queryStr.contains(kw.trim())) {
                    score++;
                }
            }
            if (score > bestScore) {
                bestScore = score;
                bestCategory = entry.getKey();
            }
        }
        return bestScore >= 2 ? bestCategory : null;
    }

    /** 提取知识条目的特征词（问题 + 关键词 + 同义词） */
    private Set<String> extractDocTokens(AiKnowledge doc) {
        Set<String> tokens = new HashSet<>();
        if (doc.getQuestion() != null) {
            tokens.addAll(tokenizer.tokenize(doc.getQuestion()));
        }
        if (doc.getKeywords() != null) {
            for (String kw : doc.getKeywords().split("[,，]")) {
                String t = kw.trim();
                if (!t.isEmpty()) {
                    tokens.add(t);
                }
            }
        }
        if (doc.getSynonyms() != null) {
            for (String syn : doc.getSynonyms().split("[,，]")) {
                String t = syn.trim();
                if (!t.isEmpty()) {
                    tokens.add(t);
                }
            }
        }
        return tokens;
    }

    // ==================== 向量索引构建 ====================

    /** 预计算所有文档的 TF-IDF 向量与全局 IDF 字典（启动/更新时调用） */
    private void buildVectorIndex() {
        Map<String, Double> newIdf = new HashMap<>();
        Map<Long, Map<String, Double>> newVectors = new HashMap<>();

        int totalDocs = knowledgeBase.size();
        if (totalDocs == 0) {
            this.idfMap = newIdf;
            this.docVectors = newVectors;
            return;
        }

        Map<Long, Map<String, Integer>> docTermFreqs = new HashMap<>();
        Map<Long, Integer> docTermCounts = new HashMap<>();
        Map<String, Integer> termDocCount = new HashMap<>();

        for (AiKnowledge doc : knowledgeBase) {
            Set<String> tokens = extractDocTokens(doc);
            if (doc.getAnswer() != null) {
                tokens.addAll(tokenizer.tokenize(doc.getAnswer()));
            }
            Map<String, Integer> freqMap = new HashMap<>();
            for (String token : tokens) {
                freqMap.merge(token, 1, Integer::sum);
            }
            int totalTerms = freqMap.values().stream().mapToInt(Integer::intValue).sum();
            docTermFreqs.put(doc.getId(), freqMap);
            docTermCounts.put(doc.getId(), totalTerms);
            for (String token : freqMap.keySet()) {
                termDocCount.merge(token, 1, Integer::sum);
            }
        }

        for (Map.Entry<String, Integer> entry : termDocCount.entrySet()) {
            double idf = Math.log((double) totalDocs / entry.getValue()) + IDF_SMOOTHING;
            newIdf.put(entry.getKey(), idf);
        }

        for (AiKnowledge doc : knowledgeBase) {
            Map<String, Double> tfIdfVector = new HashMap<>();
            Map<String, Integer> freqMap = docTermFreqs.get(doc.getId());
            Integer totalTerms = docTermCounts.get(doc.getId());
            if (freqMap != null && totalTerms != null && totalTerms > 0) {
                for (Map.Entry<String, Integer> entry : freqMap.entrySet()) {
                    double tf = (double) entry.getValue() / totalTerms;
                    double idf = newIdf.getOrDefault(entry.getKey(), Math.log(totalDocs + IDF_SMOOTHING));
                    tfIdfVector.put(entry.getKey(), tf * idf);
                }
            }
            newVectors.put(doc.getId(), tfIdfVector);
        }

        // 原子替换，避免读到半构建状态
        this.idfMap = newIdf;
        this.docVectors = newVectors;
        log.info("[本地引擎] 向量索引构建完成: {}个文档, {}个词汇", totalDocs, newIdf.size());
    }
}
