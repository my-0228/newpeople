package com.freshman.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.freshman.common.Result;
import com.freshman.entity.AiChatHistory;
import com.freshman.mapper.AiChatHistoryMapper;
import com.freshman.service.AiQaService;
import com.freshman.service.AiQaService.ChatRequest;
import com.freshman.service.AiQaService.ChatResponse;
import com.freshman.service.CurrentUserResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.*;

/**
 * AI 智能问答控制器
 * 功能：提供AI问答的REST API接口和聊天页面视图
 *
 * API接口：
 * - POST /api/ai/chat          → 发送问题，获取答案（核心接口）
 * - GET  /api/ai/quick-questions → 获取快捷提问列表
 * - GET  /api/ai/hot-questions   → 获取热门问题
 * - GET  /api/ai/categories      → 获取知识分类列表
 * - GET  /api/ai/stats           → 获取知识库统计信息
 *
 * 页面视图：
 * - GET  /ai-chat                → AI聊天页面
 *
 * 所属模块：AI 智能问答模块
 * @author AI Module Team
 * @version 1.0
 */
@Controller
public class AiQaController {

    private static final Logger log = LoggerFactory.getLogger(AiQaController.class);

    private final AiQaService aiQaService;
    private final AiChatHistoryMapper chatHistoryMapper;
    private final CurrentUserResolver currentUserResolver;

    public AiQaController(AiQaService aiQaService, AiChatHistoryMapper chatHistoryMapper,
                          CurrentUserResolver currentUserResolver) {
        this.aiQaService = aiQaService;
        this.chatHistoryMapper = chatHistoryMapper;
        this.currentUserResolver = currentUserResolver;
    }

    // ==================== 页面视图 ====================

    /**
     * AI 智能问答聊天页面
     * 功能：渲染独立的AI聊天界面，包含两栏布局（聊天区 + 快捷入口区）
     *
     * @param model Spring MVC Model
     * @return 聊天页面模板路径
     */
    @GetMapping("/ai-chat")
    public String chatPage(Model model, Principal principal) {
        model.addAttribute("title", "AI 智能问答");
        // 传给前端用于隔离 localStorage 里的会话ID：
        // 同一浏览器切换账号时，若 key 不含用户名，B 会沿用 A 的 sessionId（历史上就是这样泄露的）
        model.addAttribute("currentUser", principal == null ? "" : principal.getName());
        model.addAttribute("quickQuestions", aiQaService.getQuickQuestions());
        model.addAttribute("hotQuestions", aiQaService.getHotQuestions(10));
        model.addAttribute("categories", aiQaService.getCategories());
        return "ai-chat";
    }

    // ==================== REST API 接口 ====================

    /**
     * AI问答核心接口
     *
     * 请求示例：
     * POST /api/ai/chat
     * Content-Type: application/json
     * {"question": "宿舍有空调吗？", "sessionId": "abc123-def456"}
     *
     * 响应示例：
     * {
     *   "code": 200,
     *   "message": "操作成功",
     *   "data": {
     *     "question": "宿舍有空调吗？",
     *     "answer": "厚德学区宿舍配备空调...",
     *     "confidence": 0.8732,
     *     "category": "宿舍",
     *     "isUnknown": false,
     *     "relatedQuestions": ["宿舍有什么设施？", "住宿费多少？"]
     *   }
     * }
     *
     * @param request 用户提问请求
     * @param httpRequest HTTP请求（用于获取IP）
     * @param principal 当前登录用户（可为null，支持匿名访问）
     * @return 统一的Result包装响应
     */
    @PostMapping("/api/ai/chat")
    @ResponseBody
    public Result<ChatResponse> chat(@RequestBody(required = false) ChatRequest request,
                                      HttpServletRequest httpRequest,
                                      Principal principal) {
        // 参数校验
        if (request == null || request.getQuestion() == null || request.getQuestion().trim().isEmpty()) {
            return Result.error("问题不能为空，请输入您想问的问题");
        }

        // 问题长度限制（防止恶意超长输入）
        if (request.getQuestion().length() > 500) {
            return Result.error("问题过长，请控制在500字以内");
        }

        // 获取或生成会话ID
        String sessionId = request.getSessionId();
        if (sessionId == null || sessionId.isEmpty()) {
            sessionId = UUID.randomUUID().toString();
        }

        // 获取用户ID（登录用户的数据库主键；解析不出则为 null）
        Long userId = currentUserResolver.resolveId(principal);

        // 获取客户端IP
        String ipAddress = getClientIp(httpRequest);

        // 调用AI问答服务
        try {
            ChatResponse response = aiQaService.chat(
                    request.getQuestion().trim(), sessionId, ipAddress, userId);

            // 将会话ID回传给前端（用于多轮对话）
            response.setQuestion(request.getQuestion().trim());

            log.info("[AI API] 问答完成: session={}, isUnknown={}, confidence={}",
                    sessionId, response.getIsUnknown(), response.getConfidence());

            return Result.success(response);
        } catch (Exception e) {
            log.error("[AI API] 问答异常: {}", e.getMessage(), e);
            return Result.error("AI服务暂时不可用，请稍后再试");
        }
    }

    /**
     * 获取快捷提问列表
     * 功能：返回预设的快捷提问文本，前端展示为可点击的标签
     *
     * GET /api/ai/quick-questions
     */
    @GetMapping("/api/ai/quick-questions")
    @ResponseBody
    public Result<Map<String, Object>> quickQuestions() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("questions", aiQaService.getQuickQuestions());
        data.put("tip", "点击下方问题快速提问");
        return Result.success(data);
    }

    /**
     * 获取热门问题Top N
     * 功能：返回被询问次数最多的知识条目，帮助新生发现常见问题
     *
     * GET /api/ai/hot-questions?limit=10
     */
    @GetMapping("/api/ai/hot-questions")
    @ResponseBody
    public Result<Map<String, Object>> hotQuestions(@RequestParam(defaultValue = "10") int limit) {
        if (limit < 1) limit = 1;
        if (limit > 50) limit = 50;

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("questions", aiQaService.getHotQuestions(limit));
        data.put("limit", limit);
        return Result.success(data);
    }

    /**
     * 获取知识分类列表
     * 功能：返回所有知识分类及其关键词，用于前端分类筛选
     *
     * GET /api/ai/categories
     */
    @GetMapping("/api/ai/categories")
    @ResponseBody
    public Result<Map<String, Object>> categories() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("categories", aiQaService.getCategories());
        return Result.success(data);
    }

    /**
     * 获取知识库统计信息（管理员用）
     * 功能：返回知识库的统计信息，帮助管理员了解AI引擎运行状态
     *
     * GET /api/ai/stats
     */
    @GetMapping("/api/ai/stats")
    @ResponseBody
    public Result<Map<String, Object>> stats() {
        Map<String, Object> stats = aiQaService.getKnowledgeStats();
        return Result.success(stats);
    }

    /**
     * 重新加载知识库（管理员用）
     * 功能：管理员在后台更新知识库后，调用此接口热刷新内存中的向量索引
     *
     * POST /api/ai/reload
     */
    @PostMapping("/api/ai/reload")
    @ResponseBody
    public Result<String> reload() {
        try {
            aiQaService.reloadKnowledgeBase();
            return Result.success("知识库已重新加载");
        } catch (Exception e) {
            log.error("[AI API] 知识库重载失败: {}", e.getMessage(), e);
            return Result.error("知识库重载失败: " + e.getMessage());
        }
    }

    /**
     * 获取指定会话的历史问答记录（前端进入页面时恢复聊天上下文）
     *
     * GET /api/ai/history?sessionId=xxx&limit=50
     *
     * 安全约束（**按用户隔离，二者缺一不可**）：
     * 1. 只返回 {@code user_id = 当前登录用户} 的记录 —— sessionId 由前端提供，**不可信**；
     * 2. sessionId 额外用于"同一用户内按会话区分"；
     * 3. 解析不出当前用户时**直接返回空并告警**，绝不退化为"不加 user_id 条件"
     *    （否则会把所有人的历史都吐出去，比修复前更糟）。
     */
    @GetMapping("/api/ai/history")
    @ResponseBody
    public Result<Map<String, Object>> history(@RequestParam String sessionId,
                                               @RequestParam(defaultValue = "50") int limit,
                                               Principal principal) {
        if (limit < 1) limit = 1;
        if (limit > 200) limit = 200;

        Long userId = currentUserResolver.resolveId(principal);
        List<Map<String, Object>> messages = new ArrayList<>();

        if (userId == null) {
            // 关键：身份不明时返回空，而不是"不过滤"。宁可少给，不可多给。
            log.warn("[AI API] 无法确定当前用户，拒绝返回历史记录: session={}", sessionId);
            Map<String, Object> empty = new LinkedHashMap<>();
            empty.put("sessionId", sessionId);
            empty.put("messages", messages);
            empty.put("reason", "unauthenticated");
            return Result.success(empty);
        }

        try {
            List<AiChatHistory> list = chatHistoryMapper.selectList(
                    new LambdaQueryWrapper<AiChatHistory>()
                            .eq(AiChatHistory::getUserId, userId)      // ← 隔离条件，必须存在
                            .eq(AiChatHistory::getSessionId, sessionId.trim())
                            .eq(AiChatHistory::getIsUnknown, 0)
                            .orderByDesc(AiChatHistory::getId)
                            .last("LIMIT " + limit));
            java.util.Collections.reverse(list);
            for (AiChatHistory h : list) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("question", h.getQuestion());
                item.put("answer", h.getAnswer());
                item.put("createTime", h.getCreateTime());
                messages.add(item);
            }
        } catch (Exception e) {
            log.warn("[AI API] 获取历史记录失败: {}", e.getMessage());
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sessionId", sessionId);
        data.put("messages", messages);
        return Result.success(data);
    }

    // ==================== 辅助方法 ====================

    /**
     * 获取客户端真实IP地址
     * 考虑反向代理、负载均衡等情况
     */
    private String getClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("X-Real-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("Proxy-Client-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("WL-Proxy-Client-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        // 多级代理时取第一个非unknown的IP
        if (ip != null && ip.contains(",")) {
            ip = ip.split(",")[0].trim();
        }
        return ip;
    }
}
