package com.freshman.service.impl;

import com.freshman.entity.AiChatHistory;
import com.freshman.entity.AiKnowledge;
import com.freshman.mapper.AiChatHistoryMapper;
import com.freshman.mapper.AiKnowledgeMapper;
import com.freshman.rag.HybridRetriever;
import com.freshman.rag.LocalKnowledgeEngine;
import com.freshman.rag.RagService;
import com.freshman.rag.dto.Citation;
import com.freshman.rag.dto.RetrievalResult;
import com.freshman.rag.dto.ScoredChunk;
import com.freshman.service.AiQaService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 智能问答服务实现 —— 现在是一个**编排类**
 *
 * 职责（重构后，各能力已拆到独立组件）：
 *  - `RagService`           ：检索 + 门限 + 生成 + 引用 + 检索日志（本类不再自己发 HTTP）
 *  - `HybridRetriever`      ：双路召回 + RRF + 双门限
 *  - `LocalKnowledgeEngine` ：改造前的本地匹配算法（降级路径），独立可测
 *  - `ChineseTokenizer`     ：中文分词 + 同义词扩展（关键词检索路径共用）
 *  - 本类                   ：对外接口、**对话历史落库**、快捷/热门问题、知识库维护入口
 *
 * 保留不变的契约：
 *  - `chat(...)` 的签名与"写 ai_chat_history"的职责未变（历史接口与前端依赖它）
 *  - `retrieveContext(...)` 签名未变，实现改为委托 `HybridRetriever`
 *    （其唯一调用方 DeepSeekChatService 因此自动获得新检索能力）
 *
 * 所属模块：AI 智能问答模块
 * @author AI Module Team
 * @version 2.0
 */
@Service
public class AiQaServiceImpl implements AiQaService {

    private static final Logger log = LoggerFactory.getLogger(AiQaServiceImpl.class);

    private final AiKnowledgeMapper knowledgeMapper;
    private final AiChatHistoryMapper chatHistoryMapper;
    private final RagService ragService;
    private final HybridRetriever hybridRetriever;
    private final LocalKnowledgeEngine localEngine;

    @Value("${app.ai.llm.enabled:false}")
    private boolean llmEnabled;

    public AiQaServiceImpl(AiKnowledgeMapper knowledgeMapper,
                           AiChatHistoryMapper chatHistoryMapper,
                           RagService ragService,
                           HybridRetriever hybridRetriever,
                           LocalKnowledgeEngine localEngine) {
        this.knowledgeMapper = knowledgeMapper;
        this.chatHistoryMapper = chatHistoryMapper;
        this.ragService = ragService;
        this.hybridRetriever = hybridRetriever;
        this.localEngine = localEngine;
    }

    // ==================== 核心问答接口 ====================

    @Override
    public ChatResponse chat(String question, String sessionId, String ipAddress, Long userId) {
        if (question == null || question.trim().isEmpty()) {
            ChatResponse response = buildUnknownResponse(question);
            saveHistory(userId, sessionId, question, response.getAnswer(), null, 0.0, 1, ipAddress);
            return response;
        }
        String q = question.trim();
        log.info("[AI问答] 收到问题: {}", q);

        // 检索 + 门限 + 生成 + 引用 + 检索日志（由 RagService 完成）
        ChatResponse response = ragService.ask(q, sessionId);

        // 未知问题补上快捷提问：RagService 有意不依赖本接口（否则形成循环依赖）
        if (Boolean.TRUE.equals(response.getIsUnknown())) {
            response.setRelatedQuestions(getQuickQuestions());
        }

        // 保留改造前的"被询问次数"统计：RAG 路径下由引用来源反推命中的知识条目
        incrementViewCountIfHit(response.getCitations());

        // 对话历史落库职责不变（本类持有 userId/ip）
        saveHistory(userId, sessionId, q, response.getAnswer(), null,
                response.getConfidence(),
                Boolean.TRUE.equals(response.getIsUnknown()) ? 1 : 0, ipAddress);
        return response;
    }

    /** 命中 ai_knowledge 来源时累加其被询问次数（用于热门问题排行）；失败不影响主流程 */
    private void incrementViewCountIfHit(List<Citation> citations) {
        if (citations == null || citations.isEmpty()) {
            return;
        }
        for (Citation c : citations) {
            if ("ai_knowledge".equals(c.getSourceType()) && c.getSourceId() != null) {
                try {
                    knowledgeMapper.incrementViewCount(c.getSourceId());
                } catch (Exception e) {
                    log.debug("[AI问答] 更新被询问次数失败：{}", e.getMessage());
                }
                return;   // 只统计首要引用，避免一次问答累加多个条目
            }
        }
    }

    // ==================== RAG 检索（供外部大模型使用） ====================

    /**
     * 从知识库检索与问题最相关的材料，拼接为参考材料文本。
     *
     * 重构后改为委托 `HybridRetriever`（向量 + 关键词双路召回 + RRF + 双门限），
     * 因此其唯一调用方 `DeepSeekChatService`（useRag=true 分支）无需改动
     * 就获得了新的检索能力 —— 这正是本项目"消除同一能力两套实现"的落点。
     *
     * @param question 用户问题
     * @param topK     参考材料条数
     * @return 参考材料文本（无合格材料时返回空串）
     */
    @Override
    public String retrieveContext(String question, int topK) {
        if (question == null || question.trim().isEmpty() || topK <= 0) {
            return "";
        }
        RetrievalResult result = hybridRetriever.retrieve(question.trim(), topK);
        StringBuilder sb = new StringBuilder();
        int i = 0;
        for (ScoredChunk c : result.getChunks()) {
            String source = c.getTitle() != null ? c.getTitle() : c.getSourceType();
            sb.append("【参考材料").append(++i).append("｜来源：").append(source).append("】")
                    .append(c.getContent() == null ? "" : c.getContent().replace("\n", " "))
                    .append("\n");
        }
        log.info("[RAG检索] question={}, gateMode={}, 命中材料数={}",
                question, result.getGateMode(), i);
        return sb.toString();
    }

    // ==================== 知识库维护与统计 ====================

    @Override
    public void reloadKnowledgeBase() {
        localEngine.reload();
        log.info("[知识库] 已重新加载，当前条目数: {}", localEngine.knowledgeSize());
    }

    @Override
    public Map<String, Object> getKnowledgeStats() {
        Map<String, Object> stats = new LinkedHashMap<>(localEngine.stats());
        stats.put("llmEnabled", llmEnabled);
        return stats;
    }

    // ==================== 公共查询接口 ====================

    @Override
    public String[] getQuickQuestions() {
        return new String[]{
                "报到需要带什么材料？",
                "宿舍有空调吗？",
                "军训多长时间？",
                "怎么缴学费？",
                "有哪些奖学金？",
                "怎么加入社团？",
                "校园网怎么连？",
                "图书馆几点开放？"
        };
    }

    @Override
    public String[] getHotQuestions(int limit) {
        List<AiKnowledge> hotList = knowledgeMapper.selectHotQuestions(limit);
        return hotList.stream()
                .map(AiKnowledge::getQuestion)
                .toArray(String[]::new);
    }

    @Override
    public String[] getCategories() {
        return localEngine.categories();
    }

    // ==================== 内部辅助 ====================

    /** 空问题/无匹配时的兜底回复 */
    private ChatResponse buildUnknownResponse(String question) {
        ChatResponse response = new ChatResponse();
        response.setQuestion(question);
        response.setAnswer(
                "🤔 很抱歉，我目前的知识库中暂时没有找到与您问题直接匹配的答案。\n\n" +
                "您可以尝试以下方式获取帮助：\n" +
                "① 换一种方式提问（尝试更简洁或更具体的表述）\n" +
                "② 查看【迎新指南-常见问题】页面，那里有更多分类整理的信息\n" +
                "③ 联系辅导员或拨打招生办电话：0459-6503XXX\n" +
                "④ 在【交流社区】发帖，学长学姐会热心解答\n\n" +
                "💡 小贴士：尽量使用简短的句子提问，如【宿舍有空调吗】、【学费多少】等。"
        );
        response.setConfidence(0.0);
        response.setCategory("未知");
        response.setIsUnknown(true);
        response.setRelatedQuestions(getQuickQuestions());
        return response;
    }

    /** 保存对话历史到数据库（职责与改造前一致） */
    private void saveHistory(Long userId, String sessionId, String question,
                             String answer, Long knowledgeId, Double confidence,
                             int isUnknown, String ipAddress) {
        try {
            AiChatHistory history = new AiChatHistory();
            history.setUserId(userId);
            history.setSessionId(sessionId);
            history.setQuestion(question);
            history.setAnswer(answer);
            history.setSourceKnowledgeId(knowledgeId);
            history.setConfidence(confidence);
            history.setIsUnknown(isUnknown);
            history.setIpAddress(ipAddress);
            chatHistoryMapper.insert(history);
        } catch (Exception e) {
            log.warn("[对话历史] 保存失败: {}", e.getMessage());
        }
    }
}
