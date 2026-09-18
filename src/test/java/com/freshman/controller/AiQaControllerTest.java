package com.freshman.controller;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.freshman.common.Result;
import com.freshman.entity.AiChatHistory;
import com.freshman.mapper.AiChatHistoryMapper;
import com.freshman.service.AiQaService;
import com.freshman.service.AiQaService.ChatRequest;
import com.freshman.service.AiQaService.ChatResponse;
import com.freshman.service.CurrentUserResolver;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.security.Principal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * AI 问答的**按用户隔离**回归测试。
 *
 * 背景（真实缺陷）：`/api/ai/history` 原来只按前端传来的 `sessionId` 过滤，
 * 不做任何归属校验；同时 `getCurrentUserId()` 永远返回 null，
 * 导致 `ai_chat_history.user_id` 长期为空、无法隔离。
 * 结果是同一浏览器切换账号后，后一个账号能读到前一个账号的提问。
 *
 * 因此这里的核心断言是：**历史查询必须带上 user_id 条件**。
 * 断言 SQL 片段而不是 mock 返回值——mock 想返回什么就返回什么，
 * 只有检查真正执行的条件，才能锁住这类"参数没传/条件被跳过"的缺陷。
 */
@ExtendWith(MockitoExtension.class)
class AiQaControllerTest {

    @Mock private AiQaService aiQaService;
    @Mock private AiChatHistoryMapper chatHistoryMapper;
    @Mock private CurrentUserResolver currentUserResolver;
    @Mock private Principal principal;

    private AiQaController controller;

    /**
     * 纯单元测试没有 Spring 容器，MyBatis-Plus 的 TableInfo 缓存不会被填充，
     * 直接调 {@code wrapper.getSqlSegment()} 会抛 "can not find lambda cache for this entity"。
     * 这里手动初始化实体元信息，才能把 {@code AiChatHistory::getUserId} 解析成列名 user_id —— 
     * 而"条件里到底有没有 user_id"正是本测试要断言的东西。
     */
    @BeforeAll
    static void initMybatisPlusTableInfo() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), AiChatHistory.class);
    }

    @BeforeEach
    void setUp() {
        controller = new AiQaController(aiQaService, chatHistoryMapper, currentUserResolver);
    }

    // ==================== 历史查询：必须按用户隔离 ====================

    @Test
    @DisplayName("隔离：历史查询必须包含 user_id 条件（本轮修复的核心）")
    void historyQueryMustFilterByUserId() {
        when(currentUserResolver.resolveId(principal)).thenReturn(42L);
        when(chatHistoryMapper.selectList(any())).thenReturn(List.of());

        controller.history("sess-abc", 50, principal);

        ArgumentCaptor<LambdaQueryWrapper<AiChatHistory>> captor = wrapperCaptor();
        verify(chatHistoryMapper).selectList(captor.capture());
        String sql = captor.getValue().getSqlSegment();

        assertTrue(sql.contains("user_id"),
                "历史查询必须按 user_id 隔离，否则任何人拿到 sessionId 就能读别人的问答。实际条件：" + sql);
        assertTrue(sql.contains("session_id"),
                "同一用户内仍需按会话区分。实际条件：" + sql);
    }

    @Test
    @DisplayName("隔离：解析不出用户时返回空且**不查库**，绝不退化成不过滤")
    void historyWithoutResolvableUserReturnsEmptyAndSkipsDb() {
        when(currentUserResolver.resolveId(principal)).thenReturn(null);

        Result<Map<String, Object>> r = controller.history("sess-abc", 50, principal);

        assertEquals(200, r.getCode().intValue());
        assertEquals(List.of(), r.getData().get("messages"), "身份不明时必须返回空列表");
        assertEquals("unauthenticated", r.getData().get("reason"));
        // 关键：不能"顺手查一下"。少查一次库，胜过泄露一次数据。
        verify(chatHistoryMapper, never()).selectList(any());
    }

    @Test
    @DisplayName("隔离：查不到他人记录时返回空列表，不报错")
    void historyReturnsEmptyWhenNoRowsForThisUser() {
        when(currentUserResolver.resolveId(principal)).thenReturn(42L);
        when(chatHistoryMapper.selectList(any())).thenReturn(List.of());

        Result<Map<String, Object>> r = controller.history("sess-abc", 50, principal);

        assertEquals(List.of(), r.getData().get("messages"));
    }

    @Test
    @DisplayName("隔离：同一个 sessionId 下，查询条件必须绑定**当前用户**的 id")
    void sameSessionBindsCurrentUserId() {
        when(currentUserResolver.resolveId(principal)).thenReturn(42L);
        when(chatHistoryMapper.selectList(any())).thenReturn(List.of());

        // 故意用一个"别人的" sessionId：这种情况下唯一能救命的就是 user_id 条件
        controller.history("someone-elses-session", 50, principal);

        ArgumentCaptor<LambdaQueryWrapper<AiChatHistory>> captor = wrapperCaptor();
        verify(chatHistoryMapper).selectList(captor.capture());
        LambdaQueryWrapper<AiChatHistory> w = captor.getValue();

        assertTrue(w.getSqlSegment().contains("user_id"), "缺 user_id 条件：" + w.getSqlSegment());
        assertTrue(w.getParamNameValuePairs().containsValue(42L),
                "条件必须绑定当前用户 id=42，实际绑定参数：" + w.getParamNameValuePairs());
    }

    // ==================== 提问：必须落库真实 user_id ====================

    @Test
    @DisplayName("断言有效性自检：旧实现的条件集确实不含 user_id —— 证明上面的断言能抓住该缺陷")
    void assertionCanDetectTheOldBuggyImplementation() {
        // 复刻修复前的条件集：只有 sessionId + isUnknown
        LambdaQueryWrapper<AiChatHistory> oldImpl = new LambdaQueryWrapper<AiChatHistory>()
                .eq(AiChatHistory::getSessionId, "s")
                .eq(AiChatHistory::getIsUnknown, 0);

        String oldSql = oldImpl.getSqlSegment();
        assertFalse(oldSql.contains("user_id"),
                "如果旧条件里就有 user_id，说明本测试的断言没有区分力，需要重写。实际：" + oldSql);
        // 反向确认：新实现的条件集含 user_id（见 historyQueryMustFilterByUserId）
    }

    @Test
    @DisplayName("提问：把解析出的 user_id 传给服务层落库（否则历史永远无法隔离）")
    void chatPersistsResolvedUserId() {
        when(currentUserResolver.resolveId(principal)).thenReturn(42L);
        ChatResponse resp = new ChatResponse();
        resp.setAnswer("答案");
        when(aiQaService.chat(anyString(), anyString(), any(), eq(42L))).thenReturn(resp);

        ChatRequest req = new ChatRequest();
        req.setQuestion("宿舍有空调吗");
        req.setSessionId("sess-abc");

        Result<ChatResponse> r = controller.chat(req, mock(jakarta.servlet.http.HttpServletRequest.class), principal);

        assertEquals(200, r.getCode().intValue());
        // 断言第四参数：必须是真实 user_id，而不是历史上的 null
        verify(aiQaService).chat(eq("宿舍有空调吗"), eq("sess-abc"), any(), eq(42L));
    }

    @Test
    @DisplayName("提问：未登录时 user_id 为 null，不抛异常")
    void chatToleratesAnonymous() {
        when(currentUserResolver.resolveId(null)).thenReturn(null);
        ChatResponse resp = new ChatResponse();
        resp.setAnswer("答案");
        when(aiQaService.chat(anyString(), anyString(), any(), isNull())).thenReturn(resp);

        ChatRequest req = new ChatRequest();
        req.setQuestion("你好");

        Result<ChatResponse> r = controller.chat(req, mock(jakarta.servlet.http.HttpServletRequest.class), null);

        assertEquals(200, r.getCode().intValue());
        verify(aiQaService).chat(eq("你好"), anyString(), any(), isNull());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private ArgumentCaptor<LambdaQueryWrapper<AiChatHistory>> wrapperCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(LambdaQueryWrapper.class);
    }
}
