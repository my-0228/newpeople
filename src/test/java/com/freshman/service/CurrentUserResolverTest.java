package com.freshman.service;

import com.freshman.entity.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.security.Principal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * {@link CurrentUserResolver} 的解析与**失败即拒绝**语义。
 *
 * 这个类存在的意义是把"用户名"翻译成稳定的 user_id。
 * 它的失败语义很关键：**任何异常路径都必须返回 null，且绝不抛出**，
 * 因为调用方（历史查询）依赖 null 走"拒绝返回数据"分支。
 * 如果这里抛异常，控制器可能捕获后继续执行到"不过滤"的代码，
 * 从"查不到"变成"查出所有人"——比不修还危险。
 */
@ExtendWith(MockitoExtension.class)
class CurrentUserResolverTest {

    @Mock private UserService userService;
    @Mock private Principal principal;

    private CurrentUserResolver resolver;

    private CurrentUserResolver resolver() {
        return new CurrentUserResolver(userService);
    }

    @Test
    @DisplayName("正常：用户名 → user_id")
    void resolvesUserId() {
        when(principal.getName()).thenReturn("zhangsan");
        User u = new User();
        u.setId(42L);
        when(userService.findByUsername("zhangsan")).thenReturn(u);

        assertEquals(42L, resolver().resolveId(principal));
    }

    @Test
    @DisplayName("匿名（principal 为 null）→ null")
    void returnsNullForAnonymous() {
        assertNull(resolver().resolveId(null));
        verifyNoInteractions(userService);
    }

    @Test
    @DisplayName("用户名为空或空白 → null，且不查库")
    void returnsNullForBlankUsername() {
        when(principal.getName()).thenReturn("   ");
        assertNull(resolver().resolveId(principal));

        Principal p2 = mock(Principal.class);
        when(p2.getName()).thenReturn(null);
        assertNull(resolver().resolveId(p2));

        verifyNoInteractions(userService);
    }

    @Test
    @DisplayName("认证通过但查不到用户记录 → null")
    void returnsNullWhenUserNotFound() {
        when(principal.getName()).thenReturn("ghost");
        when(userService.findByUsername("ghost")).thenReturn(null);

        assertNull(resolver().resolveId(principal));
    }

    @Test
    @DisplayName("用户记录存在但主键为 null → null")
    void returnsNullWhenUserIdMissing() {
        when(principal.getName()).thenReturn("noid");
        when(userService.findByUsername("noid")).thenReturn(new User());

        assertNull(resolver().resolveId(principal));
    }

    @Test
    @DisplayName("查询抛异常 → 返回 null 而不是向上抛（调用方据此拒绝返回数据）")
    void swallowsExceptionAndReturnsNull() {
        when(principal.getName()).thenReturn("boom");
        when(userService.findByUsername("boom")).thenThrow(new RuntimeException("DB down"));

        assertNull(resolver().resolveId(principal),
                "异常必须被吞掉并返回 null；向上抛会让调用方难以走拒绝分支");
    }

    @Test
    @DisplayName("用户名两端空白会被 trim 后再查询")
    void trimsUsername() {
        when(principal.getName()).thenReturn("  lisi  ");
        User u = new User();
        u.setId(7L);
        when(userService.findByUsername("lisi")).thenReturn(u);

        assertEquals(7L, resolver().resolveId(principal));
    }
}
