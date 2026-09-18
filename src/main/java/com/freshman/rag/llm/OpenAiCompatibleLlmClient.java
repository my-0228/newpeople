package com.freshman.rag.llm;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * OpenAI 兼容协议的 LLM 客户端（沿用现有 app.ai.llm.* 配置：Qwen / DeepSeek / GLM 等）
 *
 * 从原 AiQaServiceImpl.callLlmApi() 抽出，保留其关键设计：
 *  - 未配置 apiKey 时不抛异常（由 isConfigured() 让编排层直接走降级）
 *  - 401/402/429 等错误码转成人话（本类只记 error 文案，展示由编排层决定）
 *  - 连接/读取超时明确设置，失败一律转 success=false
 *
 * 所属模块：AI 智能问答模块 / RAG
 */
@Component
public class OpenAiCompatibleLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleLlmClient.class);

    @Value("${app.ai.llm.enabled:false}")
    private boolean enabled;

    @Value("${app.ai.llm.apiKey:}")
    private String apiKey;

    @Value("${app.ai.llm.apiUrl:}")
    private String apiUrl;

    @Value("${app.ai.llm.model:qwen-plus}")
    private String model;

    @Value("${app.ai.llm.timeout:30000}")
    private int timeoutMs;

    @Value("${app.ai.llm.maxTokens:800}")
    private int maxTokens;

    @Value("${app.ai.llm.temperature:0.7}")
    private double temperature;

    @Override
    public boolean isConfigured() {
        return enabled && apiKey != null && !apiKey.trim().isEmpty()
                && apiUrl != null && !apiUrl.isBlank();
    }

    @Override
    public LlmResult complete(String systemPrompt, String userMessage) {
        long start = System.currentTimeMillis();
        if (!isConfigured()) {
            return LlmResult.fail("LLM 未配置（enabled=false 或 apiKey 为空）", 0);
        }

        try {
            List<Map<String, String>> messages = List.of(
                    Map.of("role", "system", "content", systemPrompt),
                    Map.of("role", "user", "content", userMessage));
            JSONObject body = new JSONObject()
                    .set("model", model)
                    .set("messages", messages)
                    .set("temperature", temperature);
            if (maxTokens > 0) {
                body.set("max_tokens", maxTokens);
            }

            HttpClient http = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofMillis(Math.min(10_000, timeoutMs)))
                    .build();
            HttpRequest request = HttpRequest.newBuilder(URI.create(apiUrl))
                    .header("Authorization", "Bearer " + apiKey.trim())
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofMillis(timeoutMs))
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> resp = http.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            long cost = System.currentTimeMillis() - start;

            if (resp.statusCode() != 200) {
                String friendly = friendlyError(resp.statusCode());
                log.warn("[RAG-LLM] API 返回 {}：{}", resp.statusCode(), truncate(resp.body(), 300));
                return LlmResult.fail(friendly, cost);
            }

            JSONObject json = JSONUtil.parseObj(resp.body());
            JSONArray choices = json.getJSONArray("choices");
            if (choices == null || choices.isEmpty()) {
                return LlmResult.fail("响应缺少 choices", cost);
            }
            JSONObject message = choices.getJSONObject(0).getJSONObject("message");
            String content = message == null ? null : message.getStr("content");
            if (content == null || content.isBlank()) {
                return LlmResult.fail("模型返回空正文（可能被 max_tokens 截断）", cost);
            }
            int tokens = 0;
            JSONObject usage = json.getJSONObject("usage");
            if (usage != null && usage.getInt("total_tokens") != null) {
                tokens = usage.getInt("total_tokens");
            }
            return LlmResult.ok(content.trim(), tokens, cost);

        } catch (java.net.http.HttpTimeoutException e) {
            return LlmResult.fail("LLM 调用超时", System.currentTimeMillis() - start);
        } catch (Exception e) {
            log.warn("[RAG-LLM] 调用异常：{}", e.getMessage());
            return LlmResult.fail("LLM 调用异常：" + e.getClass().getSimpleName(),
                    System.currentTimeMillis() - start);
        }
    }

    /** 把常见错误码转成人话（沿用改造前的分级口径） */
    private static String friendlyError(int status) {
        return switch (status) {
            case 401 -> "API Key 无效或已被删除";
            case 402 -> "账户余额不足";
            case 429 -> "请求过于频繁（限流），请稍后重试";
            default -> "LLM 服务返回异常（状态码 " + status + "）";
        };
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
