package com.freshman.rag;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * RAG 配置（前缀 app.ai.rag）
 *
 * 设计要点：
 * 1. 项目**没有** @ConfigurationPropertiesScan（FreshmanApplication 仅声明 @MapperScan），
 *    因此这里必须显式 @Component 才会被注册。
 * 2. 全部参数外部化 —— 阈值/权重/门限不再硬编码，改配置即可调参，无需重新编译。
 * 3. 非法配置在**启动期**直接失败并指明配置项。配置错误不应表现为运行期的诡异行为
 *    （例如 overlap >= size 会导致切分死循环、relative-floor > 1 会让相对门限永远不通过）。
 *
 * 所属模块：AI 智能问答模块 / RAG
 * @author AI Module Team
 * @version 1.0
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.ai.rag")
public class RagProperties {

    /** RAG 总开关；false 时 AiQaServiceImpl 退回纯本地引擎行为 */
    private boolean enabled = true;

    /** 向量召回候选数；同时是 retrieve(question, topK) 的钳制上限 */
    private int topKVector = 20;

    /** 关键词召回候选数 */
    private int topKKeyword = 20;

    /** 最终注入 prompt 的材料条数（只约束生成路径，不约束检索层） */
    private int topKFinal = 5;

    /** vector 模式绝对门限：向量路径 top1 的原始余弦下限 */
    private double minScore = 0.35;

    /** keyword 模式绝对门限：关键词路径 top1 的原始 TF-IDF 余弦下限 */
    private double keywordMinScore = 0.25;

    /** 相对门限系数：候选原始分须 >= relative-floor × top1 原始分 */
    private double relativeFloor = 0.6;

    /** RRF 平滑常数，仅影响融合排序（不参与任何门限判断） */
    private int rrfK = 60;

    /** 同一文档最多保留的 chunk 数（避免 Top-K 被同段相邻块占满） */
    private int maxPerDocument = 2;

    /** 切分参数 */
    private final Chunk chunk = new Chunk();

    /** Embedding 参数 */
    private final Embedding embedding = new Embedding();

    /** 切分参数 */
    @Data
    public static class Chunk {
        /** 单块目标字数上限 */
        private int size = 400;
        /** 相邻块重叠字数 */
        private int overlap = 60;
        /** 小于该长度的块并入相邻块，避免碎片污染召回 */
        private int minSize = 30;
    }

    /** Embedding 参数 */
    @Data
    public static class Embedding {
        /** OpenAI 兼容的 embeddings 端点 */
        private String apiUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1/embeddings";
        /** API Key；优先取环境变量 DASHSCOPE_API_KEY */
        private String apiKey = "";
        /** 模型名 */
        private String model = "text-embedding-v3";
        /** 向量维度 */
        private int dimensions = 1024;
        /** 单次请求最大条数（百炼上限 25） */
        private int batchSize = 25;
        /** 连接超时（毫秒） */
        private int connectTimeout = 10000;
        /** 读取超时（毫秒） */
        private int readTimeout = 30000;
    }

    /**
     * 启动期校验：不合法直接抛 IllegalArgumentException，并指明是哪个配置项。
     * 由 @PostConstruct 在容器启动时自动调用；单元测试可直接调用。
     */
    public void validate() {
        require(chunk.size > 0, "app.ai.rag.chunk.size 必须 > 0");
        require(chunk.minSize > 0 && chunk.minSize < chunk.size,
                "app.ai.rag.chunk.min-size 必须 ∈ (0, size)，当前 min-size=" + chunk.minSize + ", size=" + chunk.size);
        require(chunk.overlap >= 0 && chunk.overlap < chunk.size,
                "app.ai.rag.chunk.overlap 必须 ∈ [0, size)，当前 overlap=" + chunk.overlap + ", size=" + chunk.size);
        require(relativeFloor > 0 && relativeFloor <= 1,
                "app.ai.rag.relative-floor 必须 ∈ (0,1]，当前 " + relativeFloor);
        require(minScore > 0 && minScore <= 1, "app.ai.rag.min-score 必须 ∈ (0,1]，当前 " + minScore);
        require(keywordMinScore > 0 && keywordMinScore <= 1,
                "app.ai.rag.keyword-min-score 必须 ∈ (0,1]，当前 " + keywordMinScore);
        require(topKFinal <= Math.min(topKVector, topKKeyword),
                "app.ai.rag.top-k-final 不能大于 min(top-k-vector, top-k-keyword)，当前 "
                        + topKFinal + " > " + Math.min(topKVector, topKKeyword));
        require(embedding.dimensions > 0, "app.ai.rag.embedding.dimensions 必须 > 0");
        require(embedding.batchSize > 0 && embedding.batchSize <= 25,
                "app.ai.rag.embedding.batch-size 必须 ∈ [1,25]（百炼单次上限），当前 " + embedding.batchSize);
    }

    /** 容器启动时校验，使配置错误在启动期暴露（而非运行期） */
    @PostConstruct
    public void init() {
        validate();
    }

    private static void require(boolean ok, String msg) {
        if (!ok) {
            throw new IllegalArgumentException("RAG 配置非法：" + msg);
        }
    }
}
