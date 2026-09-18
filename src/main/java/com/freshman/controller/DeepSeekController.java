package com.freshman.controller;

import com.freshman.common.Result;
import com.freshman.service.AiQaService;
import com.freshman.service.AiQaService.ChatRequest;
import com.freshman.service.AiQaService.ChatResponse;
import com.freshman.agent.AgentOrchestrator;
import com.freshman.agent.AgentProperties;
import com.freshman.agent.dto.AgentAnswer;
import com.freshman.agent.dto.AgentChatResponse;
import com.freshman.agent.dto.AgentStep;
import com.freshman.service.DeepSeekChatService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * DeepSeek 智能问答控制器
 * 功能：提供 DeepSeek 问答的页面视图和 REST API
 *
 * 页面视图：
 * - GET  /deepseek-chat        → DeepSeek 聊天页面
 *
 * REST API：
 * - POST /api/deepseek/chat    → 发送问题，获取 DeepSeek 回答（核心接口）
 * - GET  /api/deepseek/status  → 查询 DeepSeek 配置状态（是否已配置apiKey）
 *
 * 所属模块：AI 智能问答模块 / DeepSeek 问答
 * @author DeepSeek Module
 * @version 1.0
 */
@Controller
public class DeepSeekController {

    private static final Logger log = LoggerFactory.getLogger(DeepSeekController.class);

    private final DeepSeekChatService deepSeekChatService;
    private final AiQaService aiQaService;
    private final AgentOrchestrator agentOrchestrator;
    private final AgentProperties agentProperties;
    private final com.freshman.agent.ToolRegistry toolRegistry;
    private final com.freshman.mapper.AgentToolCallLogMapper traceMapper;
    private final com.freshman.service.CurrentUserResolver currentUserResolver;

    public DeepSeekController(DeepSeekChatService deepSeekChatService, AiQaService aiQaService,
                              AgentOrchestrator agentOrchestrator, AgentProperties agentProperties,
                              com.freshman.agent.ToolRegistry toolRegistry,
                              com.freshman.mapper.AgentToolCallLogMapper traceMapper,
                              com.freshman.service.CurrentUserResolver currentUserResolver) {
        this.deepSeekChatService = deepSeekChatService;
        this.aiQaService = aiQaService;
        this.agentOrchestrator = agentOrchestrator;
        this.agentProperties = agentProperties;
        this.toolRegistry = toolRegistry;
        this.traceMapper = traceMapper;
        this.currentUserResolver = currentUserResolver;
    }

    // ==================== 页面视图 ====================

    /**
     * DeepSeek 问答聊天页面
     */
    @GetMapping("/deepseek-chat")
    public String chatPage(Model model, Principal principal) {
        model.addAttribute("title", "DeepSeek 问答");
        // 传给前端用于隔离 localStorage 里的会话ID（同浏览器换账号不得沿用上一个账号的会话）
        model.addAttribute("currentUser", principal == null ? "" : principal.getName());
        model.addAttribute("quickQuestions", aiQaService.getQuickQuestions());
        model.addAttribute("hotQuestions", aiQaService.getHotQuestions(10));
        model.addAttribute("categories", aiQaService.getCategories());
        model.addAttribute("configured", deepSeekChatService.isConfigured());
        return "deepseek-chat";
    }

    // ==================== REST API 接口 ====================

    /**
     * 按 turnId 回放一次提问的工具调用轨迹
     *
     * GET /api/deepseek/trace?turnId=xxx
     * 返回 Result{ data: { turnId, steps: [ {stepNo, toolName, arguments, resultDigest,
     *        durationMs, status, error, createTime} ] } }，步骤按 stepNo 升序。
     *
     * 用途：前端轨迹卡片可点击回放；也是"这次问答用了哪些工具"的核查入口。
     */
    @GetMapping("/api/deepseek/trace")
    @ResponseBody
    public Result<java.util.Map<String, Object>> trace(@RequestParam(required = false) String turnId) {
        if (turnId == null || turnId.isBlank()) {
            return Result.error("turnId 不能为空");
        }
        java.util.List<com.freshman.entity.AgentToolCallLog> steps = traceMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.freshman.entity.AgentToolCallLog>()
                        .eq(com.freshman.entity.AgentToolCallLog::getTurnId, turnId)
                        .orderByAsc(com.freshman.entity.AgentToolCallLog::getStepNo)
                        .orderByAsc(com.freshman.entity.AgentToolCallLog::getId));
        java.util.Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("turnId", turnId);
        data.put("steps", steps);
        return Result.success(data);
    }

    /**
     * Agent 问答核心接口
     *
     * 请求示例：
     * POST /api/deepseek/chat
     * Content-Type: application/json
     * {"question": "宿舍有空调吗？", "sessionId": "abc123"}
     */
    @PostMapping("/api/deepseek/chat")
    @ResponseBody
    public Result<AgentChatResponse> chat(@RequestBody(required = false) ChatRequest request,
                                          HttpServletRequest httpRequest,
                                          Principal principal) {
        if (request == null || request.getQuestion() == null || request.getQuestion().trim().isEmpty()) {
            return Result.error("问题不能为空，请输入您想问的问题");
        }
        if (request.getQuestion().length() > 500) {
            return Result.error("问题过长，请控制在500字以内");
        }

        String sessionId = request.getSessionId();
        if (sessionId == null || sessionId.isEmpty()) {
            sessionId = UUID.randomUUID().toString();
        }
        String question = request.getQuestion().trim();

        // 当前登录用户的数据库主键：落库时写入 user_id，历史/上下文据此隔离
        Long userId = currentUserResolver.resolveId(principal);

        try {
            if (agentProperties.isEnabled()) {
                AgentChatResponse resp = runAgent(question, sessionId, userId);
                log.info("[DeepSeek API] Agent 问答完成: session={}, stopReason={}, steps={}",
                        sessionId, resp.getStopReason(),
                        resp.getSteps() == null ? 0 : resp.getSteps().size());
                return Result.success(resp);
            }
            // 开关关闭 → 保持改造前行为（可回退）
            ChatResponse legacy = deepSeekChatService.chat(question, sessionId, getClientIp(httpRequest), userId);
            return Result.success(toAgentResponse(legacy, sessionId, true));
        } catch (Exception e) {
            log.error("[DeepSeek API] 问答异常: {}", e.getMessage(), e);
            return Result.error("DeepSeek 服务暂时不可用，请稍后再试");
        }
    }

    /** 走 Agent 编排；降级时退回无工具 chat（复用现有配置引导与错误码分级） */
    private AgentChatResponse runAgent(String question, String sessionId, Long userId) {
        AgentAnswer answer = agentOrchestrator.run(question, sessionId, java.util.List.of());
        if (answer.isDegraded() || answer.getAnswer() == null) {
            ChatResponse fallback = deepSeekChatService.chat(question, sessionId, null, userId);
            AgentChatResponse resp = toAgentResponse(fallback, sessionId, true);
            resp.setSteps(answer.getSteps());
            // 降级点可能发生在若干工具步骤**已经落库之后**（如 step1 工具成功、step2 模型调用失败），
            // 那些步骤是按编排器的 turnId 写的。若这里用 toAgentResponse 新生成的随机 turnId，
            // 前端拿它去 /trace 回放就会静默返回 0 步 —— 与成功路径修过的是同一个缺陷。
            if (answer.getTurnId() != null) {
                resp.setTurnId(answer.getTurnId());
            }
            return resp;
        }
        AgentChatResponse resp = new AgentChatResponse();
        resp.setQuestion(question);
        resp.setAnswer(answer.getAnswer());
        resp.setCategory("DeepSeek-Agent");
        resp.setIsUnknown(false);
        resp.setConfidence(1.0);
        // 必须复用编排器生成的 turnId —— 轨迹落库用的是它；
        // 若这里另生成一个，前端拿到的 turnId 就查不回轨迹（实测踩过）。
        resp.setTurnId(answer.getTurnId());
        resp.setSteps(answer.getSteps());
        resp.setCitations(extractCitations(answer.getSteps()));
        resp.setStopReason(answer.getStopReason());
        resp.setDegraded(false);
        resp.setCostMs(answer.getTotalMs());
        return resp;
    }

    /** 从 search_knowledge 步骤的 meta.materials 投影出引用列表 */
    @SuppressWarnings("unchecked")
    private java.util.List<AgentChatResponse.CitationView> extractCitations(java.util.List<AgentStep> steps) {
        java.util.List<AgentChatResponse.CitationView> out = new java.util.ArrayList<>();
        if (steps == null) {
            return out;
        }
        for (AgentStep s : steps) {
            if (!"search_knowledge".equals(s.getToolName()) || s.getMeta() == null) {
                continue;
            }
            Object materials = s.getMeta().get("materials");
            if (!(materials instanceof java.util.List<?> list)) {
                continue;
            }
            for (Object o : list) {
                if (!(o instanceof java.util.Map<?, ?> m)) {
                    continue;
                }
                AgentChatResponse.CitationView v = new AgentChatResponse.CitationView();
                Object idx = m.get("index");
                v.setIndex(idx instanceof Number n ? n.intValue() : out.size() + 1);
                v.setTitle((String) m.get("title"));
                v.setUrlPath((String) m.get("url_path"));
                v.setSnippet((String) m.get("snippet"));
                v.setSourceType((String) m.get("source_type"));
                Object sid = m.get("source_id");
                v.setSourceId(sid instanceof Number n ? n.longValue() : null);
                out.add(v);
            }
            break;   // 只取第一次检索的来源，避免重复
        }
        return out;
    }

    /** 旧响应 → Agent 响应（字段名保持不变，前端旧逻辑可用） */
    private AgentChatResponse toAgentResponse(ChatResponse legacy, String sessionId, boolean degraded) {
        AgentChatResponse resp = new AgentChatResponse();
        resp.setQuestion(legacy.getQuestion());
        resp.setAnswer(legacy.getAnswer());
        resp.setConfidence(legacy.getConfidence());
        resp.setCategory(legacy.getCategory());
        resp.setIsUnknown(legacy.getIsUnknown());
        resp.setRelatedQuestions(legacy.getRelatedQuestions());
        resp.setTurnId(UUID.randomUUID().toString());
        resp.setStopReason(degraded ? "degraded" : "final_answer");
        resp.setDegraded(degraded);
        resp.setCostMs(legacy.getCostMs());
        if (legacy.getCitations() != null) {
            for (com.freshman.rag.dto.Citation c : legacy.getCitations()) {
                AgentChatResponse.CitationView v = new AgentChatResponse.CitationView();
                v.setIndex(c.getIndex());
                v.setTitle(c.getTitle());
                v.setUrlPath(c.getUrlPath());
                v.setSnippet(c.getSnippet());
                v.setSourceType(c.getSourceType());
                v.setSourceId(c.getSourceId());
                resp.getCitations().add(v);
            }
        }
        return resp;
    }

    /**
     * 查询 DeepSeek 配置状态（前端用于显示配置提示横幅与当前模式徽标）
     * GET /api/deepseek/status
     *
     * 除 key 是否配置外，还如实上报**当前走的是 Agent 还是旧链路**，以及实际注册到的工具名
     * —— 工具名取自注册表而非写死，避免"页面说 5 个工具、实际只注册了 3 个"这类不一致。
     */
    @GetMapping("/api/deepseek/status")
    @ResponseBody
    public Result<Map<String, Object>> status() {
        Map<String, Object> data = new LinkedHashMap<>();
        boolean configured = deepSeekChatService.isConfigured();
        boolean agentEnabled = agentProperties.isEnabled();
        data.put("configured", configured);
        data.put("agentEnabled", agentEnabled);
        data.put("mode", agentEnabled ? "agent" : "legacy");
        data.put("tools", toolRegistry.names());
        data.put("toolCount", toolRegistry.size());
        data.put("tip", configured ? "DeepSeek 已就绪" :
                "尚未配置 DeepSeek API Key，请设置环境变量 DEEPSEEK_API_KEY 后重启应用");
        return Result.success(data);
    }

    /**
     * 获取指定会话的历史问答记录（前端进入页面时恢复聊天上下文）
     * GET /api/deepseek/history?sessionId=xxx&limit=50
     *
     * 安全约束：只返回**当前登录用户自己**的记录。sessionId 由前端提供、不可信，
     * 仅凭它查询等于"谁拿到 sessionId 谁就能读别人的问答"，历史上正是这个缺陷导致跨用户可见。
     * 身份解析不出来时返回空列表，而不是退化成"不加 user_id 条件"。
     */
    @GetMapping("/api/deepseek/history")
    @ResponseBody
    public Result<Map<String, Object>> history(@RequestParam String sessionId,
                                               @RequestParam(defaultValue = "50") int limit,
                                               Principal principal) {
        if (limit < 1) limit = 1;
        if (limit > 200) limit = 200;
        Long userId = currentUserResolver.resolveId(principal);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sessionId", sessionId);
        data.put("messages", deepSeekChatService.getHistory(sessionId, userId, limit));
        if (userId == null) {
            data.put("reason", "unauthenticated");
        }
        return Result.success(data);
    }

    // ==================== 辅助方法 ====================

    private String getClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("X-Real-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        if (ip != null && ip.contains(",")) {
            ip = ip.split(",")[0].trim();
        }
        return ip;
    }
}
