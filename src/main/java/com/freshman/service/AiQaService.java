package com.freshman.service;

import java.util.Map;

/**
 * AI 智能问答服务接口
 * 功能：定义AI问答的核心能力，包括语义匹配、LLM扩展、知识库管理等
 *
 * 核心算法：TF-IDF语义向量化 + 余弦相似度 + 多策略融合评分
 * 架构设计：本地离线引擎(默认) + 可扩展大模型API(配置启用)
 *
 * 所属模块：AI 智能问答模块
 * @author AI Module Team
 * @version 1.0
 */
public interface AiQaService {

    /**
     * AI问答请求DTO
     */
    class ChatRequest {
        private String question;
        private String sessionId;

        public String getQuestion() { return question; }
        public void setQuestion(String question) { this.question = question; }
        public String getSessionId() { return sessionId; }
        public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    }

    /**
     * AI问答响应DTO
     */
    class ChatResponse {
        private String question;
        private String answer;
        private Double confidence;
        private String category;
        private Boolean isUnknown;
        private String[] relatedQuestions;

        /** 引用来源（RAG 路径产出；前端渲染"参考来源"折叠块） */
        private java.util.List<com.freshman.rag.dto.Citation> citations;

        /** 是否降级回答（LLM 不可用，答案来自本地引擎） */
        private Boolean degraded;

        /** 本次问答总耗时（毫秒） */
        private Long costMs;

        public String getQuestion() { return question; }
        public void setQuestion(String question) { this.question = question; }
        public String getAnswer() { return answer; }
        public void setAnswer(String answer) { this.answer = answer; }
        public Double getConfidence() { return confidence; }
        public void setConfidence(Double confidence) { this.confidence = confidence; }
        public String getCategory() { return category; }
        public void setCategory(String category) { this.category = category; }
        public Boolean getIsUnknown() { return isUnknown; }
        public void setIsUnknown(Boolean isUnknown) { this.isUnknown = isUnknown; }
        public String[] getRelatedQuestions() { return relatedQuestions; }
        public void setRelatedQuestions(String[] relatedQuestions) { this.relatedQuestions = relatedQuestions; }
        public java.util.List<com.freshman.rag.dto.Citation> getCitations() { return citations; }
        public void setCitations(java.util.List<com.freshman.rag.dto.Citation> citations) { this.citations = citations; }
        public Boolean getDegraded() { return degraded; }
        public void setDegraded(Boolean degraded) { this.degraded = degraded; }
        public Long getCostMs() { return costMs; }
        public void setCostMs(Long costMs) { this.costMs = costMs; }
    }

    /**
     * 处理用户提问并返回最佳答案
     * 核心流程：分词 → TF-IDF向量化 → 多策略相似度计算 → 置信度过滤 → 组装响应
     *
     * @param question 用户原始问题
     * @param sessionId 会话标识（用于上下文关联）
     * @param ipAddress 用户IP（用于记录日志）
     * @param userId 用户ID（NULL表示匿名）
     * @return AI问答响应（含答案、置信度、分类、相关问题等）
     */
    ChatResponse chat(String question, String sessionId, String ipAddress, Long userId);

    /**
     * 获取快捷提问列表（供前端展示快捷入口）
     *
     * @return 快捷提问文本数组
     */
    String[] getQuickQuestions();

    /**
     * 获取热门问题Top N
     *
     * @param limit 返回条数
     * @return 热门问题文本数组
     */
    String[] getHotQuestions(int limit);

    /**
     * 获取所有知识分类
     *
     * @return 分类名称数组
     */
    String[] getCategories();

    /**
     * 重新加载知识库（用于管理员更新知识后热刷新）
     */
    void reloadKnowledgeBase();

    /**
     * 获取知识库状态信息
     *
     * @return 包含总数、分类统计等信息的Map
     */
    Map<String, Object> getKnowledgeStats();

    /**
     * 从本地知识库中检索与问题最相关的上下文材料（供外部大模型RAG使用）
     * 流程：分词 → 同义词扩展 → TF-IDF向量化 → 多策略融合评分 → 取TopK答案拼接
     *
     * @param question 用户问题
     * @param topK 返回的参考材料条数
     * @return 拼接好的参考材料文本（每条以【参考材料N】开头，换行分隔；无匹配时返回空串）
     */
    String retrieveContext(String question, int topK);
}
