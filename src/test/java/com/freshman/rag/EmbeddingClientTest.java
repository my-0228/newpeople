package com.freshman.rag;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * EmbeddingClient 单元测试。
 * 用 JDK 内置 com.sun.net.httpserver.HttpServer 当桩服务，**不调用真实 API、零新增依赖**。
 * 纯单元测试（不启动 Spring 上下文、不连数据库）。
 */
class EmbeddingClientTest {

    private HttpServer server;
    private RagProperties props;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        int port = server.getAddress().getPort();
        props = new RagProperties();
        props.getEmbedding().setApiUrl("http://127.0.0.1:" + port + "/v1/embeddings");
        props.getEmbedding().setApiKey("test-key");
        props.getEmbedding().setDimensions(4);
        props.getEmbedding().setBatchSize(2);
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private static void respond(HttpExchange ex, int code, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(b);
        }
    }

    @Test
    void batchResultsAreReorderedByIndexField() {
        // 故意乱序返回 index=1,0 —— 客户端必须按 index 归位，而不是按返回顺序
        server.createContext("/v1/embeddings", ex -> respond(ex, 200,
                "{\"data\":["
                        + "{\"index\":1,\"embedding\":[0,1,0,0]},"
                        + "{\"index\":0,\"embedding\":[1,0,0,0]}]}"));
        server.start();
        EmbeddingClient client = new EmbeddingClient(props);

        List<float[]> r = client.embedBatch(List.of("甲", "乙"));

        assertEquals(2, r.size());
        assertNotNull(r.get(0));
        assertNotNull(r.get(1));
        assertEquals(1.0f, r.get(0)[0], 1e-6, "第 0 条应是 index=0 的向量");
        assertEquals(1.0f, r.get(1)[1], 1e-6, "第 1 条应是 index=1 的向量");
    }

    @Test
    void returnedVectorIsL2Normalized() {
        server.createContext("/v1/embeddings", ex -> respond(ex, 200,
                "{\"data\":[{\"index\":0,\"embedding\":[3,4,0,0]}]}"));
        server.start();
        EmbeddingClient client = new EmbeddingClient(props);

        float[] v = client.embed("甲");

        assertNotNull(v);
        assertEquals(1.0, Math.sqrt(v[0] * v[0] + v[1] * v[1]), 1e-5, "必须做 L2 归一化");
        assertEquals(0.6f, v[0], 1e-6);
        assertEquals(0.8f, v[1], 1e-6);
    }

    @Test
    void retriesThenSucceeds() {
        AtomicInteger calls = new AtomicInteger();
        server.createContext("/v1/embeddings", ex -> {
            if (calls.incrementAndGet() == 1) {
                respond(ex, 500, "{\"error\":\"boom\"}");
            } else {
                respond(ex, 200, "{\"data\":[{\"index\":0,\"embedding\":[1,0,0,0]}]}");
            }
        });
        server.start();
        EmbeddingClient client = new EmbeddingClient(props);

        float[] v = assertDoesNotThrow(() -> client.embed("甲"));
        assertNotNull(v, "重试后应拿到向量");
        assertTrue(calls.get() >= 2, "应发生重试，实际调用次数=" + calls.get());
    }

    @Test
    void oneFailureDoesNotAbortWholeBatch() {
        // 整批始终失败（首次 + 2 次重试都 500）→ 该批应返回 null 占位，而不是抛异常
        AtomicInteger calls = new AtomicInteger();
        server.createContext("/v1/embeddings", ex -> {
            calls.incrementAndGet();
            respond(ex, 500, "{}");
        });
        server.start();
        EmbeddingClient client = new EmbeddingClient(props);

        List<float[]> r = assertDoesNotThrow(() -> client.embedBatch(List.of("甲", "乙")));
        assertEquals(2, r.size(), "批量结果条数必须与输入一致（失败位为 null）");
        assertTrue(r.get(0) == null || r.get(1) == null, "失败位应为 null，而不是抛异常");
        assertTrue(calls.get() >= 3, "首次 + 2 次重试都应发生，实际=" + calls.get());
    }
}
