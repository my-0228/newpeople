package com.freshman.agent;

/**
 * 工具执行上下文
 *
 * 所属模块：DeepSeek 问答 / Agent
 *
 * @param sessionId 会话标识（工具可据此做会话级缓存/限流，当前工具未使用）
 * @param userId    当前用户 id（匿名时为 null）
 * @param ipAddress 客户端 IP
 */
public record ToolContext(String sessionId, Long userId, String ipAddress) {
}
