package com.freshman.service;

import com.freshman.entity.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.security.Principal;

/**
 * 当前登录用户解析器：把 Spring Security 的 {@link Principal} 解析成数据库 {@code user_id}。
 *
 * 为什么需要它：Spring Security 的 {@code Principal.getName()} 只是**用户名**，
 * 而会话隔离、历史归属判断要的是稳定的主键 {@code user_id}。
 * 之前两个控制器里各写了一份 "getCurrentUserId"，且都直接 {@code return null}，
 * 导致 {@code ai_chat_history.user_id} 长期为空、无法做任何按用户隔离 —— 这是数据泄露的根因之一。
 *
 * 设计约束：**解析失败一律返回 null，绝不做兜底放行**。
 * 调用方拿到 null 时必须走"拒绝返回数据"的分支，而不是"不过滤条件"的分支
 * （MyBatis-Plus 的条件构造若写成 {@code .eq(userId != null, ...)}，
 * null 会让整个 user_id 条件被静默跳过，从而退化成"返回所有人数据"）。
 */
@Component
public class CurrentUserResolver {

    private static final Logger log = LoggerFactory.getLogger(CurrentUserResolver.class);

    private final UserService userService;

    public CurrentUserResolver(UserService userService) {
        this.userService = userService;
    }

    /**
     * 解析当前登录用户的 user_id。
     *
     * @param principal Spring Security 注入的当前用户（匿名访问时为 null）
     * @return 用户主键；无法确定身份时返回 {@code null}
     */
    public Long resolveId(Principal principal) {
        if (principal == null || principal.getName() == null || principal.getName().isBlank()) {
            return null;
        }
        String username = principal.getName().trim();
        try {
            User user = userService.findByUsername(username);
            if (user == null || user.getId() == null) {
                log.warn("[当前用户] 认证通过但查不到用户记录，按未登录处理：username={}", username);
                return null;
            }
            return user.getId();
        } catch (Exception e) {
            log.warn("[当前用户] 解析失败，按未登录处理：username={}, err={}", username, e.getMessage());
            return null;
        }
    }
}
