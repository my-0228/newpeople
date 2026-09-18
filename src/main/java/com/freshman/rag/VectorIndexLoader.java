package com.freshman.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 启动时加载向量索引
 *
 * 关键约定：**加载失败只告警，绝不阻断应用启动**。
 * 索引为空时检索层会自动转为 gate_mode=keyword（纯关键词模式），
 * 因此"MySQL 里还没有向量"或"Embedding 服务不可用"都不影响系统可用性。
 *
 * 所属模块：AI 智能问答模块 / RAG
 */
@Component
public class VectorIndexLoader implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(VectorIndexLoader.class);

    private final VectorIndex vectorIndex;
    private final KeywordRetriever keywordRetriever;

    public VectorIndexLoader(VectorIndex vectorIndex, KeywordRetriever keywordRetriever) {
        this.vectorIndex = vectorIndex;
        this.keywordRetriever = keywordRetriever;
    }

    @Override
    public void run(ApplicationArguments args) {
        // 两个索引各自独立加载、各自吞异常：任一失败都不阻断启动
        try {
            vectorIndex.rebuild();
            log.info("[RAG] 启动加载完成，向量索引 {} 条", vectorIndex.size());
        } catch (Exception e) {
            log.warn("[RAG] 向量索引加载失败，检索将自动退化为纯关键词模式：{}", e.getMessage());
        }
        try {
            keywordRetriever.rebuild();
            log.info("[RAG] 启动加载完成，关键词语料 {} 条", keywordRetriever.size());
        } catch (Exception e) {
            log.warn("[RAG] 关键词语料加载失败，关键词路径将不可用：{}", e.getMessage());
        }
    }
}
