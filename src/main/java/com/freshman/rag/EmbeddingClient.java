package com.freshman.rag;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 百炼 text-embedding-v3 客户端（OpenAI 兼容协议）
 *
 * 三个关键行为：
 * ① **按响应中的 index 字段对齐输入顺序** —— 不能假设服务端按输入顺序返回；
 * ② 返回前做 **L2 归一化**，使后续检索时的点积等价于余弦相似度（省一次开方）；
 * ③ **失败隔离** —— 单条失败置 null 返回，不抛异常、不中断整批，
 *    由调用方（KnowledgeIndexer）把对应 chunk 标记为 status=2 待重试。
 *
 * 选 java.net.http.HttpClient 而非 HttpURLConnection：本环境实测其 TLS 可用且 API 更清晰。
 * JSON 用 Hutool（项目已依赖，零新增依赖）。
 *
 * 所属模块：AI 智能问答模块 / RAG
 * @author AI Module Team
 * @version 1.0
 */
@Component
public class EmbeddingClient {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingClient.class);

    /** 重试次数（不含首次），配合指数退避 1s / 2s */
    private static final int MAX_RETRY = 2;

    private final RagProperties props;
    private final HttpClient http;

    public EmbeddingClient(RagProperties props) {
        this.props = props;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(props.getEmbedding().getConnectTimeout()))
                .build();
    }

    /**
     * 单条向量化。
     * @return 归一化后的向量；失败返回 null（调用方负责标记待重试）
     */
    public float[] embed(String text) {
        List<float[]> r = embedBatch(List.of(text));
        return r.isEmpty() ? null : r.get(0);
    }

    /**
     * 批量向量化：按 batch-size 分组请求。
     * @return 与输入**等长**的列表；失败位为 null（顺序与输入一致）
     */
    public List<float[]> embedBatch(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return new ArrayList<>();
        }
        List<float[]> out = new ArrayList<>(Collections.nCopies(texts.size(), null));
        int batchSize = props.getEmbedding().getBatchSize();
        if (batchSize <= 0) {
            batchSize = 25;
        }
        for (int from = 0; from < texts.size(); from += batchSize) {
            int to = Math.min(from + batchSize, texts.size());
            List<float[]> part = callWithRetry(texts.subList(from, to), from);
            for (int i = 0; i < part.size(); i++) {
                out.set(from + i, part.get(i));
            }
        }
        return out;
    }

    /** 一次批量请求，失败按指数退避重试；最终仍失败则整批置 null（不抛异常） */
    private List<float[]> callWithRetry(List<String> batch, int baseOffset) {
        for (int attempt = 0; attempt <= MAX_RETRY; attempt++) {
            try {
                return callOnce(batch);
            } catch (Exception e) {
                log.warn("[Embedding] 第 {}/{} 次调用失败（batchSize={}, offset={}）：{}",
                        attempt + 1, MAX_RETRY + 1, batch.size(), baseOffset, e.getMessage());
                if (attempt < MAX_RETRY) {
                    sleep((attempt + 1) * 1000L);   // 1s / 2s 指数退避
                }
            }
        }
        log.error("[Embedding] 批次最终失败，{}-{} 条将标记为待重试",
                baseOffset, baseOffset + batch.size());
        return new ArrayList<>(Collections.nCopies(batch.size(), null));
    }

    /** 真正的一次 HTTP 调用；任何异常向上抛，由 callWithRetry 统一处理 */
    private List<float[]> callOnce(List<String> batch) throws Exception {
        JSONObject body = new JSONObject()
                .set("model", props.getEmbedding().getModel())
                .set("input", batch)
                .set("dimensions", props.getEmbedding().getDimensions());

        HttpRequest request = HttpRequest.newBuilder(URI.create(props.getEmbedding().getApiUrl()))
                .header("Authorization", "Bearer " + props.getEmbedding().getApiKey())
                .header("Content-Type", "application/json")
                .timeout(Duration.ofMillis(props.getEmbedding().getReadTimeout()))
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> resp = http.send(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() != 200) {
            throw new IllegalStateException("HTTP " + resp.statusCode() + ": " + resp.body());
        }

        List<float[]> result = new ArrayList<>(Collections.nCopies(batch.size(), null));
        JSONArray data = JSONUtil.parseObj(resp.body()).getJSONArray("data");
        if (data == null) {
            throw new IllegalStateException("响应缺少 data 字段");
        }
        for (Object o : data) {
            JSONObject item = (JSONObject) o;
            Integer idx = item.getInt("index");
            JSONArray vec = item.getJSONArray("embedding");
            if (idx == null || vec == null || idx < 0 || idx >= batch.size()) {
                continue;   // 越界或残缺条目：丢弃而非整批失败
            }
            float[] v = new float[vec.size()];
            for (int i = 0; i < vec.size(); i++) {
                v[i] = vec.getFloat(i);
            }
            result.set(idx, l2Normalize(v));   // 关键：按 index 归位
        }
        return result;
    }

    /** L2 归一化：使后续点积等价于余弦相似度；零向量原样返回 */
    private static float[] l2Normalize(float[] v) {
        double sum = 0;
        for (float x : v) {
            sum += (double) x * x;
        }
        double norm = Math.sqrt(sum);
        if (norm == 0 || Double.isNaN(norm)) {
            return v;
        }
        float[] r = new float[v.length];
        for (int i = 0; i < v.length; i++) {
            r[i] = (float) (v[i] / norm);
        }
        return r;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
