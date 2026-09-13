package com.freshman.service;

// ---------- MyBatis-Plus ----------
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper; // MP 条件构造器：用方法引用指定查询列，避免手拼 SQL 字符串
import com.freshman.entity.AiChatHistory;        // AI 问答历史实体，对应 ai_chat_history 表
import com.freshman.mapper.AiChatHistoryMapper;  // 历史表 Mapper：负责历史记录的查询与写入
// ---------- Hutool ----------
import cn.hutool.json.JSONUtil;                  // JSON 工具：将 List<Map> 序列化为 JSON 数组字符串
// ---------- SLF4J 日志门面 ----------
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
// ---------- Spring 框架 ----------
import org.springframework.beans.factory.annotation.Value; // 从 application.yml 注入配置项
import org.springframework.stereotype.Service;             // 声明为 Service 层 Bean，交由 Spring 容器管理

import java.util.ArrayList;
import java.util.LinkedHashMap; // 保持插入顺序的 Map：保证返回前端的历史字段顺序稳定
import java.util.List;
import java.util.Map;

/**
 * DeepSeek 智能问答服务
 * 功能：调用 DeepSeek 官方 API（OpenAI 兼容格式）回答新生问题
 *
 * 【架构说明 / 答辩话术】
 * 支持两种模式，由 app.ai.deepseek.useRag 配置项切换：
 *   - useRag=true  → RAG（检索增强生成）模式：先用本地引擎从 ai_knowledge
 *                    知识库检索 Top3 相关材料注入系统提示词，DeepSeek 基于材料
 *                    回答，避免对学校信息"凭空编造"。
 *   - useRag=false → 纯大模型模式（当前默认）：问题直接发给 DeepSeek，
 *                    由模型依据自身通用知识自由回答，不依赖本地知识库。
 * 多轮上下文：每次调用前从 ai_chat_history 按 sessionId 取最近几轮成功问答，
 * 以 messages 数组（user/assistant 交替）形式一并发给模型，实现连续对话。
 * 这与通用 AiQaService 的区别：本服务不内置 Qwen/GLM 等多供应商切换，
 * 专门对接 DeepSeek，作为独立页面（/deepseek-chat）提供服务。
 *
 * 未配置 apiKey 时返回引导提示，不影响应用启动。
 *
 * 所属模块：AI 智能问答模块 / DeepSeek 问答
 * @author DeepSeek Module
 * @version 1.0
 */
@Service
public class DeepSeekChatService {

    /** SLF4J 日志对象：记录配置缺失、API 调用结果、异常等关键节点，便于排查问题 */
    private static final Logger log = LoggerFactory.getLogger(DeepSeekChatService.class);

    /** 通用 AI 问答服务：复用其 retrieveContext()（RAG 检索）与 getQuickQuestions()（推荐问题）两个能力 */
    private final AiQaService aiQaService;

    /** 问答历史 Mapper：读写 ai_chat_history 表，支撑多轮上下文与聊天记录回显 */
    private final AiChatHistoryMapper chatHistoryMapper;

    // ==================== DeepSeek 配置（前缀 app.ai.deepseek，冒号后为缺省值） ====================

    /** 功能总开关：false 时 chat() 直接走"配置引导"分支；未配置默认 false */
    @Value("${app.ai.deepseek.enabled:false}")
    private boolean enabled;

    /** API 密钥（DeepSeek 开放平台 sk- 开头密钥）；未配置默认空串 */
    @Value("${app.ai.deepseek.apiKey:}")
    private String apiKey;

    /** API 地址：DeepSeek 官方接口，兼容 OpenAI Chat Completions 协议 */
    @Value("${app.ai.deepseek.apiUrl:https://api.deepseek.com/v1/chat/completions}")
    private String apiUrl;

    /** 模型名称：默认 deepseek-v4-flash（速度快、成本低） */
    @Value("${app.ai.deepseek.model:deepseek-v4-flash}")
    private String model;

    /** 是否启用 RAG（检索本地知识库增强回答）；false = 纯大模型自由回答 */
    @Value("${app.ai.deepseek.useRag:false}")
    private boolean useRag;

    /**
     * 最大输出 token 数（额度控制）。0 = 不限制（请求中不传 max_tokens，
     * 由模型上限决定，V4 系列最高 384K）；大于 0 时传入该值控制输出长度与费用。
     */
    @Value("${app.ai.deepseek.maxTokens:0}")
    private int maxTokens;

    /**
     * 构造器注入（依赖声明为 final，保证不可变，也便于单元测试时手工构造实例）。
     * @param aiQaService        通用 AI 问答服务（RAG 检索 + 推荐问题）
     * @param chatHistoryMapper  问答历史表 Mapper
     */
    public DeepSeekChatService(AiQaService aiQaService, AiChatHistoryMapper chatHistoryMapper) {
        this.aiQaService = aiQaService;
        this.chatHistoryMapper = chatHistoryMapper;
    }

    /**
     * 是否已完成配置（开关打开且 apiKey 非空）
     * chat() 入口处用它分流：未配置 → 返回配置引导；已配置 → 正常调 API。
     * apiKey 经 trim() 判断，防止配置成纯空格仍被误认为有效。
     */
    public boolean isConfigured() {
        return enabled && apiKey != null && !apiKey.trim().isEmpty();
    }

    /**
     * DeepSeek 问答主流程：RAG检索 → 调用DeepSeek API → 保存对话历史
     *
     * @param question  用户问题
     * @param sessionId 会话ID
     * @param ipAddress 客户端IP
     * @param userId    用户ID（匿名时为null）
     * @return 问答响应
     */
    public AiQaService.ChatResponse chat(String question, String sessionId, String ipAddress, Long userId) {
        // 预处理：去除首尾空白，防止纯空格/带空格的问题被原样发给模型浪费额度
        question = question.trim();

        // ---- 分支一：未配置 API Key → 返回友好配置引导，不抛异常 ----
        // 设计考虑：AI 问答属演示性功能，配置缺失不应引发 500 错误，
        // 而是给用户一条清晰的配置步骤指引 + 推荐问题兜底
        if (!isConfigured()) {
            AiQaService.ChatResponse resp = new AiQaService.ChatResponse();
            resp.setQuestion(question);
            resp.setAnswer("⚙️ DeepSeek 问答功能尚未配置 API Key。\n\n" +
                    "请按以下步骤完成配置：\n" +
                    "① 访问 https://platform.deepseek.com 注册并登录\n" +
                    "② 左侧菜单「API keys」→「创建 API key」\n" +
                    "③ 复制以 sk- 开头的密钥\n" +
                    "④ 粘贴到 application.yml 的 app.ai.deepseek.apiKey 配置项\n" +
                    "⑤ 重启应用后即可使用\n\n" +
                    "💡 在此之前，你可以使用首页的「AI 智能问答」（哈基油油子），功能同样完整。");
            resp.setConfidence(0.0);
            resp.setCategory("配置提示");
            resp.setIsUnknown(true);
            resp.setRelatedQuestions(aiQaService.getQuickQuestions()); // 推荐问题兜底，引导用户转用通用问答
            log.warn("[DeepSeek问答] 未配置 apiKey，返回配置引导");
            return resp;
        }

        // ---- 分支二：构建系统提示词（由 useRag 开关决定是否注入知识库检索材料） ----
        String systemPrompt;
        if (useRag) {
            // RAG 模式：先从本地知识库检索 Top3 相关材料，拼进系统提示词约束模型"按材料回答"
            String context = aiQaService.retrieveContext(question, 3);
            systemPrompt = "你是东北石油大学智慧迎新系统的DeepSeek智能助手，专门为大一新生解答入学相关问题。" +
                    "回答要求：①优先基于参考材料准确回答 ②如果参考材料不足以回答，可以结合常识补充，但不要编造学校具体信息 " +
                    "③友好、简洁、用中文 ④可适当使用emoji。\n\n" +
                    "以下是学校知识库中检索到的参考材料：\n" + (context.isEmpty() ? "（无匹配材料，请谨慎回答）" : context);
            log.info("[DeepSeek问答] RAG模式，已注入知识库检索材料");
        } else {
            // 纯大模型模式：不检索知识库，约定模型对不确定的学校具体信息如实说明，降低幻觉风险
            systemPrompt = "你是东北石油大学智慧迎新系统的DeepSeek智能助手，专门为大一新生解答入学相关问题。" +
                    "回答要求：①友好、亲切、简洁、用中文 ②可适当使用emoji和排版让回答易读 " +
                    "③结合你的通用知识尽力回答，涉及学校具体信息（如确切日期、费用）时如不确定请如实说明。";
            log.info("[DeepSeek问答] 纯大模型模式（未启用RAG），直接调用API");
        }

        // ---- 组装多轮对话 messages：[system] + [历史问答 user/assistant 交替] + [本轮 user] ----
        // OpenAI 协议按 role 组织消息；历史必须按时间正序排列，模型才能理解对话先后关系
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", systemPrompt));  // ① 系统提示词固定放最前
        List<AiChatHistory> history = recentSuccessHistory(sessionId, 6); // ② 最近 6 条（3 轮）成功问答
        for (AiChatHistory h : history) {
            messages.add(Map.of("role", "user", "content", h.getQuestion()));
            messages.add(Map.of("role", "assistant", "content", h.getAnswer()));
        }
        messages.add(Map.of("role", "user", "content", question)); // ③ 本轮问题必须是最后一条 user 消息
        if (!history.isEmpty()) {
            log.info("[DeepSeek问答] 已携带 {} 轮历史上下文", history.size());
        }

        // ---- 构建 OpenAI 兼容格式请求体（DeepSeek 官方API兼容此格式） ----
        // temperature=0.7：适中随机度，兼顾回答稳定性与多样性
        // thinking.type=disabled：V4 系列默认开启思考模式，思考内容走 reasoning_content 字段
        // 且会消耗 max_tokens 导致正文 content 为空，问答场景显式关闭（更快更省）
        // maxTokens>0 时才传 max_tokens 限制输出长度；0/不配置 = 不限制输出额度
        // messages 用 JSONUtil.toJsonStr 整体序列化，自动处理引号/换行等转义
        String requestBody;
        if (maxTokens > 0) {
            requestBody = String.format(
                    "{\"model\":\"%s\",\"messages\":%s,\"temperature\":0.7,\"max_tokens\":%d,\"thinking\":{\"type\":\"disabled\"}}",
                    model,
                    JSONUtil.toJsonStr(messages),
                    maxTokens
            );
        } else {
            requestBody = String.format(
                    "{\"model\":\"%s\",\"messages\":%s,\"temperature\":0.7,\"thinking\":{\"type\":\"disabled\"}}",
                    model,
                    JSONUtil.toJsonStr(messages)
            );
        }

        AiQaService.ChatResponse resp = new AiQaService.ChatResponse(); // 统一响应对象
        resp.setQuestion(question);
        resp.setCategory("DeepSeek"); // 前端据此区分回答来源

        try {
            // ① 建立 HTTP 连接：JDK 原生 HttpURLConnection，不引入额外 HTTP 客户端依赖
            java.net.URL url = new java.net.URL(apiUrl);
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");                                       // Chat Completions 为 POST 接口
            conn.setRequestProperty("Content-Type", "application/json");         // 请求体为 JSON
            conn.setRequestProperty("Authorization", "Bearer " + apiKey.trim()); // 鉴权头：Bearer + 密钥
            conn.setDoOutput(true);        // 允许写出请求体
            conn.setConnectTimeout(10000); // 建连超时 10s：网络不通时快速失败
            conn.setReadTimeout(30000);    // 读超时 30s：等待模型生成回答的上限

            // ② 写出请求体：显式按 UTF-8 编码，避免中文按平台默认编码发送导致乱码/400
            java.io.OutputStream os = conn.getOutputStream();
            os.write(requestBody.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            os.flush();
            os.close();

            // ③ 读取 HTTP 状态码，按状态码分支处理
            int code = conn.getResponseCode();
            if (code == 200) {
                // 200：读取响应体并提取回答正文
                String body = readStream(conn.getInputStream());
                String content = extractJsonField(body, "content");
                if (content == null || content.isEmpty()) {
                    // 兜底：若服务端开启了思考模式，正文可能为空、回答在 reasoning_content 中
                    content = extractJsonField(body, "reasoning_content");
                }
                if (content != null && !content.isEmpty()) {
                    // 成功路径：填充回答 + 固定高置信度 + 推荐问题，并落库保存历史
                    resp.setAnswer(content);
                    resp.setConfidence(0.95);  // 大模型直答，无独立评分环节，给固定置信度
                    resp.setIsUnknown(false);  // 标记为有效回答（历史上下文只取 isUnknown=0 的记录）
                    resp.setRelatedQuestions(aiQaService.getQuickQuestions());
                    log.info("[DeepSeek问答] 回答成功, 长度={}", content.length());
                    saveHistory(userId, sessionId, question, content, ipAddress);
                    return resp; // 成功即返回，不再走底部兜底逻辑
                }
                log.warn("[DeepSeek问答] 响应中未找到content字段: {}",
                        body.substring(0, Math.min(200, body.length()))); // 只截前200字符入日志，防刷屏
                // content 仍为空：给出友好提示，避免 answer 为 null
                resp.setAnswer("😅 DeepSeek 返回了空回答（可能是思考内容过长被截断），请重试一次。");
            } else if (code == 401) {
                // 401 Unauthorized：API Key 无效或已被删除
                resp.setAnswer("🔑 DeepSeek API Key 无效或已被删除，请到 https://platform.deepseek.com 检查后更新 application.yml 配置。");
            } else if (code == 402) {
                // 402 Payment Required：账户余额不足（DeepSeek 特有的状态码语义）
                resp.setAnswer("💰 DeepSeek 账户余额不足，请充值后重试（注册赠送额度用完时会报此错误）。");
            } else if (code == 429) {
                // 429 Too Many Requests：触发平台限流
                resp.setAnswer("⏳ 请求过于频繁（触发限流），请稍等几秒再试。");
            } else {
                // 其余状态码：读错误流拿详情记日志，对用户只给通用提示
                String errBody = conn.getErrorStream() != null ? readStream(conn.getErrorStream()) : "";
                log.warn("[DeepSeek问答] API返回 {}: {}", code, errBody);
                resp.setAnswer(" DeepSeek 服务返回异常（状态码 " + code + "），请稍后再试。");
            }
        } catch (java.net.SocketTimeoutException e) {
            // 超时单独捕获：可给出比"通用异常"更精确的提示
            log.warn("[DeepSeek问答] API调用超时");
            resp.setAnswer("⏱️ DeepSeek 响应超时，请稍后重试。");
        } catch (Exception e) {
            // 其余异常：DNS 解析失败、连接被拒、网络中断等
            log.warn("[DeepSeek问答] API调用异常: {}", e.getMessage());
            resp.setAnswer("🚫 无法连接 DeepSeek 服务，请检查网络后重试。\n\n（" + e.getMessage() + "）");
        }

        // ---- 兜底出口：以上任一失败分支最终都落到这里统一收尾 ----
        // 统一标记为无效回答（isUnknown=true）、置信度 0，并同样落库，保证前端刷新历史可见本次交互
        resp.setConfidence(0.0);
        resp.setIsUnknown(true);
        resp.setRelatedQuestions(aiQaService.getQuickQuestions());
        saveHistory(userId, sessionId, question, resp.getAnswer(), ipAddress);
        return resp;
    }

    // ==================== 辅助方法 ====================

    /**
     * 查询指定会话最近 N 轮成功的问答记录（按时间正序返回）
     * 仅取 isUnknown=0 的记录，配置引导/服务异常等无效回答不进入模型上下文
     *
     * 查询思路：先按 id 倒序取最近 limit 条（自增主键越大越新），
     * 再 reverse 成时间正序——既拿到"最近的 N 条"，又保证喂给模型的
     * 对话顺序与真实先后一致
     */
    private List<AiChatHistory> recentSuccessHistory(String sessionId, int limit) {
        try {
            // 等价 SQL：SELECT * FROM ai_chat_history
            //           WHERE session_id = ? AND is_unknown = 0
            //           ORDER BY id DESC LIMIT N
            List<AiChatHistory> list = chatHistoryMapper.selectList(
                    new LambdaQueryWrapper<AiChatHistory>()
                            .eq(AiChatHistory::getSessionId, sessionId) // 条件1：同一会话
                            .eq(AiChatHistory::getIsUnknown, 0)         // 条件2：只要成功回答
                            .orderByDesc(AiChatHistory::getId)          // 排序：按主键倒序 = 最新在前
                            .last("LIMIT " + limit));                   // last()：在 SQL 末尾直接追加 LIMIT 片段
            java.util.Collections.reverse(list); // 倒序结果反转为时间正序（旧→新）
            return list;
        } catch (Exception e) {
            // 历史查询失败不阻断主流程：退化为无上下文的单轮问答
            log.warn("[DeepSeek问答] 查询历史上下文失败: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 获取指定会话的历史问答（供前端恢复聊天记录用）
     * 查询逻辑与 recentSuccessHistory 一致：isUnknown=0、按 id 倒序取最近 limit 条后反转为正序
     * @return [{question, answer, createTime}]，按时间正序
     */
    public List<Map<String, Object>> getHistory(String sessionId, int limit) {
        // 会话ID为空直接返回空列表，避免无意义的数据库查询
        if (sessionId == null || sessionId.trim().isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        try {
            List<AiChatHistory> list = chatHistoryMapper.selectList(
                    new LambdaQueryWrapper<AiChatHistory>()
                            .eq(AiChatHistory::getSessionId, sessionId.trim())
                            .eq(AiChatHistory::getIsUnknown, 0)
                            .orderByDesc(AiChatHistory::getId)
                            .last("LIMIT " + limit));
            java.util.Collections.reverse(list);
            // 实体 → Map：只挑前端需要的三个字段（LinkedHashMap 保持字段输出顺序稳定）
            for (AiChatHistory h : list) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("question", h.getQuestion());
                item.put("answer", h.getAnswer());
                item.put("createTime", h.getCreateTime());
                result.add(item);
            }
        } catch (Exception e) {
            // 查询失败返回已收集的部分结果（通常为空），不打断前端渲染
            log.warn("[DeepSeek问答] 获取历史记录失败: {}", e.getMessage());
        }
        return result;
    }

    /**
     * 保存一条问答记录到 ai_chat_history 表（多轮上下文与历史回显的数据来源）
     * 失败只记日志不抛异常：历史落库属旁路功能，不能影响问答主流程
     */
    private void saveHistory(Long userId, String sessionId, String question, String answer, String ipAddress) {
        try {
            AiChatHistory history = new AiChatHistory();
            history.setUserId(userId);       // 登录用户ID，匿名访问时为 null
            history.setSessionId(sessionId); // 会话ID（前端生成），用于串联一次连续对话
            history.setQuestion(question);
            // answer 为数据库非空字段，空值给占位文本，避免 INSERT 报错
            history.setAnswer(answer == null || answer.isEmpty() ? "（无有效回答）" : answer);
            history.setConfidence(null);     // DeepSeek 直答不做置信度评估，存 null
            history.setIsUnknown(0);         // 0 = 已发生并已回答的记录
            history.setIpAddress(ipAddress); // 客户端IP，用于统计/风控
            chatHistoryMapper.insert(history); // MP 通用插入方法
        } catch (Exception e) {
            log.warn("[DeepSeek问答] 保存历史失败: {}", e.getMessage());
        }
    }

    /**
     * 将输入流按 UTF-8 逐行读出并拼接为字符串（用于读取 DeepSeek API 响应体）
     * 逐行读取可正确处理较大响应；换行符被丢弃——JSON 对物理换行不敏感，安全
     */
    private String readStream(java.io.InputStream is) throws java.io.IOException {
        // InputStreamReader 桥接字节流→字符流，显式按 UTF-8 解码（与 API 响应编码一致）
        java.io.BufferedReader br = new java.io.BufferedReader(
                new java.io.InputStreamReader(is, java.nio.charset.StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) {
            sb.append(line);
        }
        br.close(); // 关闭流，释放连接资源
        return sb.toString();
    }

    /**
     * 转义JSON字符串特殊字符
     * 把反斜杠、双引号、换行等替换为 JSON 转义序列，保证拼进 JSON 字符串后合法。
     * 说明：请求体现在已改用 JSONUtil.toJsonStr(messages) 整体序列化（自动转义），
     * 本方法作为备用工具保留，当前无调用方。
     */
    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\") // 反斜杠必须最先替换，避免后续转义符被二次转义
                .replace("\"", "\\\"") // 双引号 → \"
                .replace("\n", "\\n")  // 换行 → \n
                .replace("\r", "\\r")  // 回车 → \r
                .replace("\t", "\\t"); // 制表符 → \t
    }

    /**
     * 从JSON字符串提取指定字段值（适配OpenAI兼容格式，无第三方JSON依赖）
     *
     * 为什么手写解析而不用 Jackson？响应结构简单、只需取一个字段，
     * 手写可避免为一个小需求引入额外 JSON 库依赖。
     *
     * 算法说明：
     * 1. 从头到尾扫描 "fieldName" 键的所有出现位置（响应中同名键可能多次出现）；
     * 2. 每处定位其后的冒号，跳过空格确定值起点；
     * 3. 值以引号开头 → 按字符串解析：扫到"未被反斜杠转义的引号"为止，
     *    再逐个反转义 \" \n \r \t \\；
     *    值非引号开头 → 按字面量解析：读到 , } ] 任一结束符为止；
     * 4. 记录"最后一个非空值"（lastValue）——越靠后出现越接近真正的
     *    message.content；扫描结束后返回 lastValue，找不到返回 null。
     */
    private String extractJsonField(String json, String fieldName) {
        String key = "\"" + fieldName + "\""; // 目标键的原始形态，如 "content"
        int keyIdx = json.indexOf(key);       // 第一次出现的位置
        String lastValue = null;              // 记录最后一个非空匹配值
        while (keyIdx != -1) {
            int colonIdx = json.indexOf(":", keyIdx); // 定位键后的冒号，其后即值
            if (colonIdx == -1) break;
            int valueStart = colonIdx + 1;
            while (valueStart < json.length() && json.charAt(valueStart) == ' ') valueStart++; // 跳过冒号后的空格
            if (valueStart >= json.length()) break;

            if (json.charAt(valueStart) == '"') {
                // ---- 字符串值：寻找闭合引号（需跳过 \" 转义引号） ----
                int valueEnd = valueStart + 1;
                while (valueEnd < json.length()) {
                    // 当前字符是引号、且前一个字符不是反斜杠 → 真正的闭合引号
                    if (json.charAt(valueEnd) == '"' && json.charAt(valueEnd - 1) != '\\') break;
                    valueEnd++;
                }
                // 截取引号内文本，并逐项反转义为真实字符
                String val = json.substring(valueStart + 1, valueEnd)
                        .replace("\\\"", "\"")  // \"  → "
                        .replace("\\n", "\n")   // \n  → 换行
                        .replace("\\r", "\r")   // \r  → 回车
                        .replace("\\t", "\t")   // \t  → 制表符
                        .replace("\\\\", "\\"); // \\  → 反斜杠
                if (!val.isEmpty()) lastValue = val; // 空值不覆盖已有结果
            } else {
                // ---- 非字符串值（数字/布尔/null）：读到 , } ] 结束符为止 ----
                int valueEnd = valueStart;
                while (valueEnd < json.length() && ",}]".indexOf(json.charAt(valueEnd)) == -1) valueEnd++;
                String val = json.substring(valueStart, valueEnd).trim();
                if (!val.isEmpty()) lastValue = val;
            }
            // 从当前值之后继续找下一个同名键 → 实现"取最后一个非空值"
            keyIdx = json.indexOf(key, valueStart);
        }
        return lastValue;
    }
}
