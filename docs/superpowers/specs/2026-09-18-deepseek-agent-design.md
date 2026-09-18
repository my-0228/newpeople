# DeepSeek 问答模块 Agent 化设计

- 日期：2026-09-18
- 范围：子系统 B —— `DeepSeek 问答`（`/deepseek-chat`）改造为 Agent（工具调用型智能体）项目
- 前置依赖：`2026-09-18-ai-qa-rag-design.md`（本设计的 `search_knowledge` 工具直接注入该设计产出的 `HybridRetriever`）
- 实施顺序：**子系统 A 完成后开始**

---

## 1. 背景与现状诊断

### 1.1 代码事实

| 事实 | 位置 |
|---|---|
| DeepSeek 问答为**单次** Chat Completions 调用 | `DeepSeekChatService.java:112-190` |
| 请求体只含 `model/messages/temperature/thinking`，**无 `tools` 字段** | `DeepSeekChatService.java:176-190` |
| 多轮上下文：按 `sessionId` 取最近 6 条成功问答拼 messages | `DeepSeekChatService.java:158-168` |
| RAG 由 `useRag` 开关控制，当前 `false`（纯大模型） | `application.yml:124` |
| 错误码分级友好化（401/402/429） | `DeepSeekChatService.java:239-250` |
| 未配置 apiKey 时返回配置引导，不抛异常 | `DeepSeekChatService.java:119-136` |
| 权限：仅 TEACHER / ADMIN | `SecurityConfig.java:71-72` |
| 页面：`deepseek-chat.html`，历史接口 `GET /api/deepseek/history` | `DeepSeekController.java:51-131` |

### 1.2 现状定性

该项目当前**不存在任何 Agent 能力**：全项目 `grep -i "tools|tool_calls|function call"` 在 Java 代码中零命中；`BaiduNavigationService` 中的 "Agent Plan" 是百度侧接口命名，本系统只是调用方，不构成自研 Agent。

判断 Agent 的四个特征 —— **自主规划、工具调用、多步执行循环、依据观察结果决定下一步** —— 当前实现一个都不具备。

### 1.3 本次改造的额外收益（架构叙事）

改造前：两个问答页面是**同一能力的重复实现且行为不一致**（`AiQaServiceImpl.callLlmApi()` 无条件 RAG，`DeepSeekChatService` 受 `useRag` 控制且为 `false`）—— 这是项目面试文档中已被点名的缺陷。

改造后：

| 页面 | 定位 | 特征 |
|---|---|---|
| `/ai-chat` | **RAG 问答** | 离线优先、可溯源、可拒答、有评估 |
| `/deepseek-chat` | **Agent 问答** | 能调工具、多步推理、轨迹可查 |

从"重复实现"变成"职责分离"，这是本设计的主要叙事收益。

---

## 2. 目标与非目标

### 2.1 目标（全部可测）

| 编号 | 目标 | 判定方式 |
|---|---|---|
| A1 | 实现 OpenAI 兼容的 **function-calling 循环** | 单测中以脚本化 LLM 响应驱动，完成 ≥2 步工具调用并产出最终答案 |
| A2 | 提供 **4 类真实工具**（检索/结构化查询/路线/通用） | 每个工具均有独立单测，且能真实执行 |
| A3 | 支持**并行 tool_calls** 与 `tool_call_id` 精确对齐 | `AgentOrchestratorTest` 覆盖一次返回 2 个工具调用的场景 |
| A4 | **幻觉工具名自愈**：调用不存在的工具不抛异常 | 回填 `{"error":"unknown_tool"}` 后模型可继续，测试断言无异常且循环继续 |
| A5 | **参数校验**：schema 不合法时回填错误而不执行 | `QueryCampusDataToolTest` 验证非法/缺参被拒绝 |
| A6 | **循环上限与预算护栏**：`maxSteps=5` + token 预算 | 超限返回中间结果摘要而非异常/无限循环 |
| A7 | **轨迹落库 + 前端展示**工具调用链路 | `agent_tool_call_log` 有记录；`deepseek-chat.html` 渲染可折叠轨迹卡片 |
| A8 | **只读安全**：Agent 无法修改任何业务数据 | 所有工具无写操作；`query_campus_data` 走枚举白名单，注入尝试被拒 |
| A9 | **降级不破坏现状**：DeepSeek 不可用时退化为无工具普通 chat | 断 key 后页面仍可用，标注"未启用工具" |
| A10 | 零新增 Maven 依赖 | `pom.xml` 依赖列表不变 |

### 2.2 非目标

见 §9 YAGNI。

---

## 3. 架构

```
用户提问
   │
   ▼
AgentOrchestrator.run(question, sessionId)
   │
   ├─ 构建 messages：[system(含工具使用纪律)] + 历史(3轮) + [user]
   │
   └─ 循环 step = 1..maxSteps(5)
        │
        ├─→ DeepSeekClient.chat(messages, tools, tool_choice=auto)
        │        │
        │        ├─ tool_calls 为空 ──→ 得到最终答案，跳出循环
        │        │
        │        └─ tool_calls 非空 ──→ 逐个处理：
        │                 │
        │                 ├─ ToolRegistry.get(name)
        │                 │     ├─ 不存在 ──→ 回填 {"error":"unknown_tool"},  不抛异常
        │                 │     └─ 存在  ──→ 参数校验
        │                 │                    ├─ 不合法 ──→ 回填 {"error":"invalid_arguments"}
        │                 │                    └─ 合法  ──→ execute() ──→ 结果截断/摘要
        │                 │
        │                 ├─ 落 agent_tool_call_log（step, tool, args, digest, ms, status）
        │                 └─ messages += [assistant(tool_calls), tool(call_id, result)]
        │
        ▼
   AgentAnswer{answer, steps[], citations[], stopReason, totalMs, degraded}
        │
        ├─→ ai_chat_history（category='DeepSeek-Agent'）
        └─→ agent_tool_call_log（每步一行）
```

### 3.1 工具清单

| 工具名 | 入参（JSON Schema） | 底层实现 | 用途 |
|---|---|---|---|
| `search_knowledge` | `query: string (required)`, `top_k: integer (1-10, default 3)` | **`HybridRetriever`（子系统 A 产出）** | 非结构化知识问答，返回带来源的材料 |
| `query_campus_data` | `entity: enum{dormitory, cafeteria, club, building, major, registration_step} (required)`, `filters: object (可选，字段白名单)` | MyBatis-Plus 白名单只读查询 | 结构化事实查询："厚德学区物业费多少" |
| `plan_route` | `from: string (required)`, `to: string (required)` | 复用 `BaiduNavigationService` | "从宿舍到食堂怎么走" |
| `get_current_time` | 无参 | JDK `LocalDateTime` | 时间类问题（模型无法自行获知） |
| `calculate` | `expression: string (required)` | 受限表达式求值（仅 `+ - * / ( ) 数字`，正则白名单，**不用脚本引擎**） | 算术（避免模型心算出错） |

> 说明：A2 要求"4 类工具"，`get_current_time` 与 `calculate` 合并计为一类"通用工具"，共 5 个工具、4 类。

**`query_campus_data` 安全设计（必被追问）**：

1. `entity` 是**枚举**，映射到固定的 Service/Mapper 方法与输出字段白名单，**不存在自由 SQL 拼接路径**
2. `filters` 的键必须在该 entity 的字段白名单内，值一律走 MyBatis-Plus 参数化条件（`LambdaQueryWrapper.eq`），注入尝试（如 `filters: {"name":"' OR 1=1 --"}`）只会被当作普通字符串值匹配，匹配不到数据
3. 结果行数上限 20，单字段长度截断 500 字，整体结果序列化后 ≤ 4000 字
4. 未注册 entity 直接抛业务异常 → 被编排层捕获并回填为 `tool` 错误消息（不冒泡给用户）

### 3.2 工具契约

```java
public interface AgentTool {
    String name();                              // 唯一名，需通过重名校验
    String description();                       // 给模型看的说明，决定它是否/何时调用
    Map<String, Object> parameters();           // JSON Schema，Hutool JSONObject 手工构造
    ToolResult execute(Map<String, Object> args, ToolContext ctx);
}

// ToolResult: { boolean success, String content, Map<String,Object> meta }
// ToolContext: { String sessionId, Long userId, String ipAddress }
```

`ToolRegistry`：

- 构造注入 `List<AgentTool>`（Spring 自动收集所有实现 Bean）
- 启动时校验：**重名检测**（冲突直接启动失败）、`name` 符合 `^[a-z_]{3,40}$`、`parameters()` 非空且含 `type/properties`
- 生成 OpenAI 兼容数组：`[{"type":"function","function":{"name":...,"description":...,"parameters":...}}]`
- `get(name)` 未命中返回 `Optional.empty()`，由编排层转成错误回填

### 3.3 编排循环与边界情况（本设计最核心部分）

```java
AgentAnswer run(String question, String sessionId, Long userId, String ip);
```

`maxSteps` 默认 5。六个必须处理的边界情况：

| # | 边界情况 | 处理方式 |
|---|---|---|
| 1 | **幻觉工具名** | `ToolRegistry.get()` 未命中 → 回填 `{"error":"unknown_tool","available":[...]}`，**不抛异常**，让模型自我纠正 |
| 2 | **参数非法/缺参/类型错** | 按 schema 校验（必填项、类型、枚举、数值区间）→ 回填 `{"error":"invalid_arguments","detail":...}` |
| 3 | **并行 tool_calls** | 一次响应含多个 `tool_calls` 时**逐个执行并逐个回填**，每条 `tool` 消息的 `tool_call_id` 必须与 assistant 消息中的 id 精确一致（否则下一轮 API 返回 400） |
| 4 | `reasoning_content` 与 tool_calls 共存 | 若响应含 `reasoning_content`，**不回填到 messages**（会污染后续轮次）；仅记录日志。当前 `thinking.type=disabled`，但需兼容模型侧开启的情形（参考 DeepSeek 官方 thinking+tool_call 示例） |
| 5 | **工具结果过长** | 单工具结果 > 4000 字则截断并附 `…[已截断，完整结果 N 字]`；`search_knowledge` 的 `top_k` 上限 10 |
| 6 | **token 预算 / 步数上限** | 循环累计 `usage.total_tokens` 超过 `token-budget`（默认 30000）或达到 `maxSteps` → 停止循环，返回 `stopReason=budget_exhausted|max_steps` + 已有中间结果摘要（不抛异常、不静默丢失） |

其他工程要求：

- 每步请求超时 30s（沿用现有口径），单步失败记为 `status=error` 并回填错误，允许模型重试其它路径
- 最终答案必须来自模型的最后一条无 `tool_calls` 的响应；若循环结束仍无最终答案，则用已收集的工具结果拼装兜底答案并标注 `stopReason`
- **所有工具只读**（A8）：不提供任何写库/发消息/改状态的工具

### 3.4 轨迹与持久化

新表（脚本 `docs/sql/2026-09-18_agent_schema.sql`）：

```sql
CREATE TABLE `agent_tool_call_log` (
  `id`           bigint       NOT NULL AUTO_INCREMENT,
  `session_id`   varchar(64)  DEFAULT NULL,
  `turn_id`      varchar(64)  NOT NULL COMMENT '一次提问的唯一标识，同一次提问的多步共享',
  `step_no`      int          NOT NULL COMMENT '第几步',
  `tool_name`    varchar(50)  NOT NULL,
  `arguments`    varchar(1000) DEFAULT NULL COMMENT '模型给出的原始参数 JSON',
  `result_digest` varchar(1000) DEFAULT NULL COMMENT '结果摘要（截断后）',
  `duration_ms`  int          DEFAULT NULL,
  `status`       varchar(20)  NOT NULL COMMENT 'success/error/invalid_arguments/unknown_tool',
  `error`        varchar(500) DEFAULT NULL,
  `create_time`  datetime     DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_session` (`session_id`),
  KEY `idx_turn` (`turn_id`),
  KEY `idx_tool` (`tool_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Agent 工具调用轨迹表';
```

- 最终答案仍写现有 `ai_chat_history`，`category='DeepSeek-Agent'`，`GET /api/deepseek/history` 保持兼容
- 新增 `GET /api/deepseek/trace?turnId=xxx` 供前端回放轨迹

### 3.5 降级与护栏

| 场景 | 行为 |
|---|---|
| 未配置 apiKey / enabled=false | 复用现有配置引导（`DeepSeekChatService.java:119-136`），标注"未启用工具" |
| DeepSeek 不可用/超时 | 降级为**无工具普通 chat**（现有行为），`degraded=true` |
| 模型不支持 `tools` 参数 | 首次调用返回 400 且报文含 tools 相关错误 → 自动降级为无工具模式并记录（不重试死循环） |
| 多步循环异常 | 捕获后返回已完成步骤的摘要 + 明确说明，不留白屏 |

护栏参数：`maxSteps=5`（配置）、`token-budget=30000`、单步超时 30s、`search_knowledge.top_k ≤ 10`、工具结果 ≤ 4000 字。

### 3.6 前端 `deepseek-chat.html`

- 每条回答下方「🔧 工具调用轨迹」折叠卡片，每步一行：
  `① search_knowledge({"query":"宿舍空调"}) → 命中 3 条 · 320ms`
  展开可看结果摘要；`status=error` 的步骤标红
- 工具产生的引用（`search_knowledge` 返回的来源）渲染为可点击来源列表
- 页头显示当前模式：`Agent 模式（5 个工具可用）` / `普通对话模式（未启用工具）`

---

## 4. 配置项

```yaml
app:
  ai:
    deepseek:
      # 现有项保持不变
      enabled: true
      apiKey: "${DEEPSEEK_API_KEY:}"
      apiUrl: "https://api.deepseek.com/v1/chat/completions"
      model: "deepseek-v4-flash"
      useRag: false          # Agent 模式下由 search_knowledge 工具按需检索，此项保留仅为兼容
      timeout: 30000
      maxTokens: 0
      # 新增 Agent 项
      agent:
        enabled: true
        max-steps: 5
        token-budget: 30000
        tool-result-max-chars: 4000
        history-rounds: 3
        trace-enabled: true
```

`agent.enabled=false` 时行为与改造前完全一致（保证可回退）。

---

## 5. 接口

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/deepseek-chat` | TEACHER/ADMIN | 页面，新增模式标识与轨迹区 |
| POST | `/api/deepseek/chat` | TEACHER/ADMIN | **响应结构向后兼容**，新增 `steps[]` / `stopReason` / `degraded` |
| GET | `/api/deepseek/status` | TEACHER/ADMIN | 新增 `agentEnabled` / `tools[]` |
| GET | `/api/deepseek/history` | TEACHER/ADMIN | 不变 |
| GET | `/api/deepseek/trace` | TEACHER/ADMIN | 新增，按 `turnId` 回放轨迹 |

权限保持现状（仅 TEACHER/ADMIN）。

---

## 6. 测试计划

| 测试类 | 覆盖 | 是否花钱 |
|---|---|---|
| `ToolRegistryTest` | schema 生成正确、重名检测触发启动失败、非法 name 拒绝 | 否 |
| `QueryCampusDataToolTest` | 白名单拒绝未知 entity、行数上限 20、字段截断、**注入尝试（`1=1` / 引号闭合）被当普通值** | 否 |
| `PlanRouteToolTest` | 复用三级容灾：真实路线 / 地点澄清 / Haversine 兜底 | 否 |
| `CalculateToolTest` | 合法表达式、含字母/分号/脚本的表达式被拒 | 否 |
| `SearchKnowledgeToolTest` | `top_k` 越界钳制、空结果返回可读提示 | 否 |
| `AgentOrchestratorTest` | **脚本化 LLM 响应重放**：单工具 / 并行双工具 / 幻觉工具名自愈 / 参数非法 / `reasoning_content` 不污染 / maxSteps 截断 / token 预算截断 / 循环内异常降级 | 否 |
| `AgentDemoScenariosTest` | 5 个演示场景真实调用，`@Tag("eval")` 手动跑 | 是 |

**脚本化重放**是本设计的测试核心：`DeepSeekClient` 抽象为接口，测试注入 `ScriptedDeepSeekClient`（按预设序列返回 tool_calls → 最终答案），从而**在不消耗 API 额度的前提下验证整个循环的所有分支**。

集成验证（我本地执行）：`mvn test` 全绿 + 启动应用后按 §7 场景逐个 `curl` 验证（真实 API，费用可控：5 个场景 × 约 3 步）。

---

## 7. 演示用例 = 验收标准

| # | 提问 | 期望调用序列 | 验证目标 |
|---|---|---|---|
| 1 | 厚德学区宿舍多少钱一年？有空调吗？从那儿走到第一食堂多远？ | `query_campus_data(dormitory)` → `plan_route` | 跨工具、多步 |
| 2 | 报到要带什么材料？从大庆站怎么到学校？ | `search_knowledge` → `plan_route` | RAG 接缝 + 工具 |
| 3 | 军训服不合身怎么办？ | `search_knowledge`（单步） | 基础路径 |
| 4 | 你们学校食堂好吃吗？ | `search_knowledge` → 空结果 → 如实说明 | 不编造 |
| 5 | 今天几号？军训 14 天的话结束是哪天？ | `get_current_time` → `calculate` | 工具优于心算 |

答辩演示话术要点：**同一个问题，Agent 调了 2 个工具给出可溯源答案，而纯 chat 只能凭记忆作答** —— 轨迹卡片就是证据。

---

## 8. 新增文件

```
com.freshman.agent
  AgentTool.java                 工具契约
  ToolRegistry.java              注册、校验、schema 生成
  ToolContext.java / ToolResult.java
  AgentOrchestrator.java         循环 + 边界处理 + 轨迹落库
  AgentProperties.java           @ConfigurationProperties(app.ai.deepseek.agent)
  tool/SearchKnowledgeTool.java
  tool/QueryCampusDataTool.java
  tool/PlanRouteTool.java
  tool/UtilityTools.java         get_current_time + calculate
  dto/AgentStep.java / AgentAnswer.java
DeepSeekClient.java              LLM 调用抽象（真实实现 + 可注入的测试实现）
```

---

## 9. 明确不做（YAGNI）

| 不做 | 理由 |
|---|---|
| Plan-and-Execute / 任务规划 | 属"进阶档"，本次为工具型 Agent，规划能力由模型在循环内隐式完成 |
| 长期记忆 / 跨会话记忆 / 向量化历史 | 需要额外存储与召回设计，与本次目标无关 |
| 多 Agent 协作 / 角色分工 | 单人项目演示收益低、复杂度高 |
| Agent 写操作类工具（改数据、发通知） | 安全风险，且与"只读护栏"目标冲突 |
| 引入 Spring AI / LangChain4j | 路线 A 已定：手写循环才能讲透；框架会掩盖 `tool_call_id` 对齐等关键机制 |
| 流式输出（SSE） | Agent 多步场景下流式收益低、改动大 |
| 放开 `/deepseek-chat` 权限给新生 | 保持 TEACHER/ADMIN 现状 |
| 统一三处会话管理（浮窗/AI页/DeepSeek页） | 独立技术债，不在本次范围 |

---

## 10. 风险与缓解

| 风险 | 影响 | 缓解 |
|---|---|---|
| DeepSeek 当前模型（`deepseek-v4-flash`）对 `tools` 的支持行为未知 | A1 阻塞 | **M0 第一步验证**：发一个带 `tools` 的最小请求，确认返回 `tool_calls`；不支持则换模型或降级为"提示词模拟工具"（会削弱 A1，需重新决策） |
| 并行 tool_calls 的 `tool_call_id` 对齐错误 | 下一轮 API 400 | 单测专门覆盖；日志记录 messages 结构摘要 |
| 模型陷入工具循环（反复调同一工具） | 烧 token | `maxSteps` + `token-budget` 双保险；`agent_tool_call_log` 可事后分析 |
| 真实演示成本 | 费用 | 单测全部走脚本化重放；真实调用仅限 §7 的 5 个场景 |
| 工具结果污染上下文（超长/含特殊字符） | 模型输出劣化 | 截断 + 摘要；`reasoning_content` 不回填 |
| 结构化查询泄露敏感字段 | 数据安全 | 输出字段白名单（不含手机号、身份证等）；`sys_user` 相关一律不开放 |
| 依赖子系统 A 未完成 | 无法开始 | 顺序固定为先 A 后 B；`search_knowledge` 是唯一强依赖，其余 3 类工具可独立开发 |

---

## 11. 里程碑

| 里程碑 | 内容 | 完成判据 |
|---|---|---|
| **M0 前置验证** | 验证 DeepSeek 模型 `tools` 支持、返回结构、`tool_call_id` 行为 | 一次成功的 tool_calls 往返（含并行调用） |
| **M1 工具层** | `AgentTool` / `ToolRegistry` / 5 个工具 / 4 类单测 | `ToolRegistryTest` 等 5 个测试类全绿 |
| **M2 编排循环** | `DeepSeekClient` 抽象、`AgentOrchestrator`、6 个边界情况 | `AgentOrchestratorTest` 全绿（含 8 个场景） |
| **M3 轨迹与前端** | `agent_tool_call_log`、trace 接口、轨迹卡片、模式标识 | `curl` 看到 2 步轨迹；页面渲染正确 |
| **M4 演示与收尾** | 5 个场景实测、降级验证、文档与话术更新 | §7 场景全部通过 + 断 key 降级不报错 |

---

## 12. 完成定义（DoD）

- [ ] `docs/sql/2026-09-18_agent_schema.sql` 可幂等执行
- [ ] `pom.xml` 依赖列表未变（A10）
- [ ] §6 全部非 eval 测试通过；`AgentOrchestratorTest` 覆盖全部 6 个边界情况
- [ ] §7 的 5 个场景真实链路跑通，轨迹可在页面看到
- [ ] §2.1 的 A1–A10 逐条有证据
- [ ] `query_campus_data` 的注入尝试实测被拒（有测试输出）
- [ ] 断 key / `agent.enabled=false` 时行为与改造前一致（可回退）
- [ ] 全部工具只读（代码审查确认无写操作）
