package com.freshman.agent.llm;

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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * OpenAI 兼容的 DeepSeek 客户端（带 tools）
 *
 * 沿用现有 `app.ai.deepseek.*` 配置；错误码分级沿用改造前口径（401/402/429 转人话）。
 *
 * 所属模块：DeepSeek 问答 / Agent
 */
@Component
public class OpenAiCompatibleDeepSeekClient implements DeepSeekClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleDeepSeekClient.class);

    @Value("${app.ai.deepseek.enabled:false}")
    private boolean enabled;

    @Value("${app.ai.deepseek.apiKey:}")
    private String apiKey;

    @Value("${app.ai.deepseek.apiUrl:https://api.deepseek.com/v1/chat/completions}")
    private String apiUrl;

    @Value("${app.ai.deepseek.model:deepseek-v4-flash}")
    private String model;

    @Value("${app.ai.deepseek.timeout:30000}")
    private int timeoutMs;

    @Value("${app.ai.deepseek.maxTokens:0}")
    private int maxTokens;

    @Override
    public boolean isConfigured() {
        return enabled && apiKey != null && !apiKey.trim().isEmpty()
                && apiUrl != null && !apiUrl.isBlank();
    }

    @Override
    public ChatTurn chat(List<Map<String, Object>> messages, String toolsJson) {
        if (!isConfigured()) {
            return ChatTurn.fail("DeepSeek 未配置（enabled=false 或 apiKey 为空）");
        }
        try {
            JSONObject body = new JSONObject()
                    .set("model", model)
                    .set("messages", messages)
                    .set("temperature", 0.7)
                    // Agent 场景显式关闭思考模式：思考内容走 reasoning_content，
                    // 会消耗 max_tokens 且不应回填进 messages
                    .set("thinking", Map.of("type", "disabled"));
            if (toolsJson != null && !toolsJson.isBlank() && !"[]".equals(toolsJson)) {
                body.set("tools", JSONUtil.parseArray(toolsJson));
                body.set("tool_choice", "auto");
            }
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
            if (resp.statusCode() != 200) {
                String friendly = switch (resp.statusCode()) {
                    case 401 -> "API Key 无效或已被删除";
                    case 402 -> "账户余额不足";
                    case 429 -> "请求过于频繁（限流）";
                    default -> "DeepSeek 返回异常（状态码 " + resp.statusCode() + "）";
                };
                log.warn("[Agent] DeepSeek 返回 {}：{}", resp.statusCode(), truncate(resp.body(), 300));
                return ChatTurn.fail(friendly);
            }

            JSONObject json = JSONUtil.parseObj(resp.body());
            JSONArray choices = json.getJSONArray("choices");
            if (choices == null || choices.isEmpty()) {
                return ChatTurn.fail("响应缺少 choices");
            }
            JSONObject choice = choices.getJSONObject(0);
            JSONObject message = choice.getJSONObject("message");
            String content = message == null ? null : message.getStr("content");
            String finishReason = choice.getStr("finish_reason");

            List<ChatTurn.ToolCall> calls = new ArrayList<>();
            JSONArray toolCalls = message == null ? null : message.getJSONArray("tool_calls");
            if (toolCalls != null) {
                for (Object o : toolCalls) {
                    JSONObject tc = (JSONObject) o;
                    JSONObject fn = tc.getJSONObject("function");
                    if (fn == null) {
                        continue;
                    }
                    // arguments 是 **JSON 字符串**（不是对象），此处原样传出，由编排层二次解析
                    calls.add(new ChatTurn.ToolCall(tc.getStr("id"), fn.getStr("name"),
                            fn.getStr("arguments")));
                }
            }

            int tokens = 0;
            JSONObject usage = json.getJSONObject("usage");
            if (usage != null && usage.getInt("total_tokens") != null) {
                tokens = usage.getInt("total_tokens");
            }
            // reasoning_content 刻意不返回给编排层：它不得回填进 messages
            return new ChatTurn(content, calls, tokens, finishReason, null);

        } catch (java.net.http.HttpTimeoutException e) {
            return ChatTurn.fail("DeepSeek 调用超时");
        } catch (Exception e) {
            log.warn("[Agent] DeepSeek 调用异常：{}", e.getMessage());
            return ChatTurn.fail("DeepSeek 调用异常：" + e.getClass().getSimpleName());
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
