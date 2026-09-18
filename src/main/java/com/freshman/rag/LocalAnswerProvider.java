package com.freshman.rag;

import java.util.Optional;

/**
 * 本地答案提供者（降级路径的抽象）
 *
 * 语义：**与改造前完全一致的本地匹配** —— 作用于 `ai_knowledge` 表，
 * 含同义词扩展、Jaccard/TF-IDF 融合评分、分类加成与 priority 加成，阈值 0.25。
 *
 * 为什么是接口：`RagService` 需要"LLM 失败时退到本地引擎"，而本地引擎的实现
 * 恰恰就是改造前的 `AiQaServiceImpl` 内部逻辑。若让 `RagService` 直接依赖
 * `AiQaServiceImpl`，而后者又要委托 `RagService`，就会形成循环依赖。
 * 用这个窄接口把方向反过来即可（Task 5 会抽出 `LocalKnowledgeEngine` 实现它）。
 *
 * 所属模块：AI 智能问答模块 / RAG
 */
public interface LocalAnswerProvider {

    /** 返回本地引擎的最佳答案；低于置信度阈值或无匹配时返回 empty */
    Optional<LocalAnswer> best(String question);

    /**
     * 本地答案
     *
     * @param knowledgeId 命中的知识条目 id（用于历史记录溯源，可为 null）
     * @param answer      答案正文
     * @param category    分类
     * @param confidence  置信度（0~1）
     */
    record LocalAnswer(Long knowledgeId, String answer, String category, double confidence) {}
}
