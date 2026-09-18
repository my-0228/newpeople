package com.freshman.it;

import com.freshman.service.AiQaService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * **降级路径的真实性验证**（G8 / DoD：断网、断 key 时不报错、不阻断启动）。
 *
 * 用 `properties` 覆盖强制"无 key"（比依赖环境变量更确定、可重复）：
 *  - `app.ai.rag.embedding.api-key=` ：问题向量化必失败 → 向量路径不可用
 *  - `app.ai.llm.enabled=false`      ：生成层不可用 → 走降级
 *
 * 期望链路：向量不可用 → gateMode=keyword → 关键词路径给出材料 → 生成层未配置
 *          → 降级到 LocalKnowledgeEngine（= 改造前的本地匹配）→ 答案带"离线降级"前缀、
 *          degraded=true、应用**正常启动**。
 *
 * 本类不需要任何 API key，也不应被 key 的存在与否影响。
 */
@Tag("it")
@SpringBootTest(properties = {
        "app.ai.rag.embedding.api-key=",
        "app.ai.llm.enabled=false",
        "app.ai.llm.apiKey="
})
class RagDegradationIT {

    @Autowired
    private AiQaService aiQaService;

    @Autowired
    private JdbcTemplate jdbc;

    /** 上下文能起来本身就是"不阻断启动"的证据（能跑到这里就说明启动成功） */
    @Test
    void contextStartsAndDegradedAnswerComesFromLocalEngine() {
        String session = "it-degrade-" + UUID.randomUUID();

        AiQaService.ChatResponse resp =
                assertDoesNotThrow(() -> aiQaService.chat("宿舍有空调吗", session, "127.0.0.1", null),
                        "无 key 时问答不能抛异常");

        assertNotNull(resp.getAnswer());
        assertFalse(resp.getAnswer().isBlank(), "降级也必须给出答案");

        Map<String, Object> log = jdbc.queryForMap(
                "SELECT gate_mode, top_score, degraded, is_unknown, generate_ms, embedding_ms "
                        + "FROM ai_retrieval_log WHERE session_id = ? ORDER BY id DESC LIMIT 1", session);
        String gateMode = (String) log.get("gate_mode");
        System.out.println("[降级验证] gate_mode=" + gateMode
                + " top_score=" + log.get("top_score")
                + " degraded=" + log.get("degraded")
                + " is_unknown=" + log.get("is_unknown")
                + " generate_ms=" + log.get("generate_ms")
                + " embedding_ms=" + log.get("embedding_ms"));
        System.out.println("[降级验证] 答案=" + resp.getAnswer());

        // 向量路径不可用 → 必须走 keyword 门限
        assertEquals("keyword", gateMode, "embedding 不可用时应转为关键词门限模式");

        // 生成层未配置 → 必须标记降级，且答案来自本地引擎
        assertEquals(Boolean.TRUE, resp.getDegraded(), "LLM 未配置时必须标记 degraded=true");
        assertTrue(resp.getAnswer().contains("离线降级"), "降级答案应有明确前缀：" + resp.getAnswer());
        assertTrue(resp.getAnswer().contains("空调") || resp.getAnswer().contains("厚德"),
                "降级答案应来自本地引擎（改造前的本地匹配）：" + resp.getAnswer());
    }

    /** 语料完全无覆盖的问题，在降级模式下也应给出可读回复而不是报错 */
    @Test
    void unanswerableQuestionStillReturnsReadableAnswerWithoutKeys() {
        String session = "it-degrade2-" + UUID.randomUUID();

        AiQaService.ChatResponse resp =
                assertDoesNotThrow(() -> aiQaService.chat("学校有没有高尔夫球场", session, "127.0.0.1", null));

        assertNotNull(resp.getAnswer());
        assertFalse(resp.getAnswer().isBlank(), "无覆盖问题也应返回可读文案，而不是空白或异常");
        System.out.println("[降级验证] 无覆盖问题答案=" + resp.getAnswer());
    }
}
