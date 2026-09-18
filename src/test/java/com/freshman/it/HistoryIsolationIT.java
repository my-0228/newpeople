package com.freshman.it;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.freshman.entity.AiChatHistory;
import com.freshman.mapper.AiChatHistoryMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 问答历史的**跨用户隔离**真库验证。
 *
 * 复现的缺陷：改造前 `/api/ai/history` 与 `/api/deepseek/history` 只按
 * 前端传来的 `sessionId` 过滤（`WHERE session_id = ? AND is_unknown = 0`），
 * 而 `sessionId` 存在浏览器的 localStorage 里 —— 它是**按浏览器**存的，不是**按账号**存的。
 * 于是同一个浏览器换了账号后，B 会沿用 A 的 sessionId，直接把 A 的提问读出来。
 *
 * 本测试用真实 MySQL 造出"两个用户、同一个 sessionId"的场景，
 * 分别用**旧条件**和**新条件**查询，用数据证明前者泄露、后者隔离。
 */
@Tag("it")
@SpringBootTest
class HistoryIsolationIT {

    @Autowired
    private AiChatHistoryMapper chatHistoryMapper;

    /** 负数列造测试数据，避免与真实用户 id 冲突 */
    private static final long USER_A = -900001L;
    private static final long USER_B = -900002L;

    private String session;

    @BeforeEach
    void seedTwoUsersSharingOneSession() {
        session = "isolation-it-" + UUID.randomUUID();
        insert(USER_A, "A 的私密提问：我的学号是多少", "A 的答案");
        insert(USER_B, "B 的私密提问：我的宿舍在哪", "B 的答案");
    }

    @AfterEach
    void cleanup() {
        chatHistoryMapper.delete(new LambdaQueryWrapper<AiChatHistory>()
                .eq(AiChatHistory::getSessionId, session));
    }

    @Test
    @DisplayName("复现：只按 sessionId 查（修复前的条件）会把两个用户的数据都返回")
    void oldSessionOnlyConditionLeaksAcrossUsers() {
        List<AiChatHistory> rows = chatHistoryMapper.selectList(
                new LambdaQueryWrapper<AiChatHistory>()
                        .eq(AiChatHistory::getSessionId, session)   // 修复前：只有这一个条件
                        .eq(AiChatHistory::getIsUnknown, 0));

        assertEquals(2, rows.size(),
                "同一个 sessionId 下确实存在两个用户的数据 —— 这正说明 sessionId 不能用来做隔离");
        long distinctUsers = rows.stream().map(AiChatHistory::getUserId).distinct().count();
        assertEquals(2, distinctUsers, "数据里确实混着两个用户");
    }

    @Test
    @DisplayName("修复后：按 user_id + sessionId 查，只能看到自己的记录")
    void newConditionIsolatesByUser() {
        List<AiChatHistory> mine = queryFor(USER_A, session);
        List<AiChatHistory> others = queryFor(USER_B, session);

        assertEquals(1, mine.size(), "A 只应看到自己那 1 条");
        assertEquals(1, others.size(), "B 只应看到自己那 1 条");

        assertTrue(mine.stream().allMatch(h -> USER_A == h.getUserId()),
                "A 的结果里不得混入他人数据");
        assertTrue(others.stream().allMatch(h -> USER_B == h.getUserId()),
                "B 的结果里不得混入他人数据");

        assertNotEquals(mine.get(0).getQuestion(), others.get(0).getQuestion(),
                "两个用户拿到的应该是各自的问题");
    }

    @Test
    @DisplayName("修复后：即使拿着别人的 sessionId，也查不到任何数据（拿到别人的会话ID也读不到）")
    void cannotReadOthersSessionEvenWithTheirSessionId() {
        // 模拟：B 拿到了 A 的 sessionId（同浏览器换账号的真实场景）
        // 但查询条件绑定的是 B 自己 —— 结果只可能是 B 自己的记录
        List<AiChatHistory> bLookingAtSharedSession = queryFor(USER_B, session);

        assertTrue(bLookingAtSharedSession.stream().noneMatch(h -> USER_A == h.getUserId()),
                "B 不得通过共享的 sessionId 读到 A 的提问");

        // 换一个从未见过的用户，任何 sessionId 都查不到东西
        List<AiChatHistory> stranger = queryFor(-900099L, session);
        assertTrue(stranger.isEmpty(), "陌生用户查任何 sessionId 都应为空");
    }

    private List<AiChatHistory> queryFor(long userId, String sessionId) {
        return chatHistoryMapper.selectList(
                new LambdaQueryWrapper<AiChatHistory>()
                        .eq(AiChatHistory::getUserId, userId)      // ← 隔离条件
                        .eq(AiChatHistory::getSessionId, sessionId)
                        .eq(AiChatHistory::getIsUnknown, 0));
    }

    private void insert(long userId, String question, String answer) {
        AiChatHistory h = new AiChatHistory();
        h.setUserId(userId);
        h.setSessionId(session);
        h.setQuestion(question);
        h.setAnswer(answer);
        h.setConfidence(0.9);
        h.setIsUnknown(0);
        h.setIpAddress("127.0.0.1");
        chatHistoryMapper.insert(h);
    }
}
