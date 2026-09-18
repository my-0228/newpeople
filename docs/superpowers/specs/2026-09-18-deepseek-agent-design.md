# DeepSeek 问答模块 Agent 化设计

- 日期：2026-09-18
- 范围：子系统 B —— `DeepSeek 问答`（`/deepseek-chat`）改造为 Agent（工具调用型智能体）项目
- 前置依赖：`2026-09-18-ai-qa-rag-design.md`（本设计的 `search_knowledge` 工具直接注入该设计产出的 `HybridRetriever`）
- 实施顺序：**子系统 A 完成后开始**
- 修订记录：v2 —— 按规格评审意见修订（`plan_route` 地点解析方案、移除 `ai_chat_history.category` 错误假设、surefire 配置、参数 JSON 解析失败路径、`stopReason` 枚举、跨规格契约、修改文件清单）

---

## 0. M0 前置验证结论（已实测，不是假设）

| 验证项 | 结果 | 证据 |
|---|---|---|
| `deepseek-v4-flash` 是否支持 `tools` | ✅ **HTTP 200**，`finish_reason="tool_calls"` | 返回 `tool_calls[0].function.name="query_campus_data"`、`arguments="{\"entity\": \"dormitory\"}"` |
| 工具调用参数格式 | ✅ 确认为 **JSON 字符串**（需二次解析） | 同上：`arguments` 是带转义的字符串，不是对象 |
| `usage` 字段可用性 | ✅ 返回 `prompt_tokens/completion_tokens/total_tokens` | 可用于 token 预算控制 |
| Embedding（子系统 A 依赖） | ✅ HTTP 200 / 1024 维 | 见子系统 A 设计 §0 |
| 构建链路 | ✅ 编译通过（缓存 Maven 3.9.12 + JBR 25） | `javac` exit 0 |
| **本环境 TLS 限制** | ⚠️ `.NET`/`curl` 无法建 TLS；**Java 正常** | 外部 API 验证走 Java；`curl` 仅用于 localhost 明文 |

---

## 1. 背景与现状诊断

### 1.1 代码事实

| 事实 | 位置 |
|---|---|
| DeepSeek 问答为**单次** Chat Completions 调用 | `DeepSeekChatService.java:112-190` |
| 请求体只含 `model/messages/temperature/thinking`，**无 `tools` 字段** | `DeepSeekChatService.java:176-190` |
| 多轮上下文：按 `sessionId` 取最近 6 条成功问答拼 messages | `DeepSeekChatService.java:158-168` |
| 未配置 apiKey 时返回配置引导（**是 `chat()` 内的一个分支，不是可复用方法**） | `DeepSeekChatService.java:119-136` |
| 错误码分级友好化（401/402/429） | `DeepSeekChatService.java:237-251` |
| 权限：仅 TEACHER / ADMIN | `SecurityConfig.java:71-72` |
| 路由方法**只接受坐标**，且请求把起点硬编码为"从我的位置" | `BaiduNavigationService.java:70-72`、`:82-86` |
| 项目中**不存在任何地理编码/地点搜索**能力 | 全库 grep 地理编码/geocoding = 0 命中 |
| `campus_building` 是全库**唯一带经纬度**的表（12 行） | `freshman_orientation.sql:210-221` |
| `life_dormitory` **完全没有地址/坐标字段** | `Dormitory.java` |
| `life_cafeteria.location` 只是描述文本（如"厚德学区附近"） | `freshman_orientation.sql:512` |
| `ai_chat_history` **没有 `category` 列** | `freshman_orientation.sql:22-38`：`id/user_id/session_id/question/answer/source_knowledge_id/confidence/is_unknown/ip_address/create_time` |
| Mapper 扫描限定包 `com.freshman.mapper` | `FreshmanApplication.java:24` |
| 项目**没有** `@ConfigurationPropertiesScan` | `FreshmanApplication.java` |
| `pom.xml` **没有 surefire 配置** | grep `surefire` 零命中 |
| **`src/test` 目录不存在** | 测试基建从零建立 |

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
| A1 | 实现 OpenAI 兼容的 **function-calling 循环** | 单测以脚本化 LLM 响应驱动，完成 ≥2 步工具调用并产出最终答案 |
| A2 | 提供 **4 类共 5 个真实工具**（检索/结构化查询/路线/通用×2） | 每个工具均有独立单测，且能真实执行 |
| A3 | 支持**并行 tool_calls** 与 `tool_call_id` 精确对齐 | `AgentOrchestratorTest` 覆盖一次返回 2 个工具调用 |
| A4 | **幻觉工具名自愈**：调用不存在的工具不抛异常 | 回填 `{"error":"unknown_tool"}` 后循环继续，测试断言无异常 |
| A5 | **参数校验**：schema 违规 **与 JSON 解析失败**均回填错误而不执行 | `QueryCampusDataToolTest` 覆盖非法/缺参/非 JSON 字符串 |
| A6 | **循环上限与预算护栏**：`maxSteps=5` + token 预算 | 超限返回中间结果摘要而非异常/无限循环 |
| A7 | **轨迹落库 + 前端展示** | `agent_tool_call_log` 有记录；页面渲染可折叠轨迹卡片 |
| A8 | **只读安全**：Agent 无法修改任何业务数据 | 所有工具无写操作（代码审查 + 无写操作类依赖）；`query_campus_data` 注入尝试被拒 |
| A9 | **降级不破坏现状**：DeepSeek 不可用时退化为无工具普通 chat | 断 key 后页面仍可用，标注"未启用工具" |
| A10 | 零新增 Maven 依赖 | `pom.xml` 的 `<dependencies>` 不变（仅新增 surefire 插件配置） |
| A11 | `plan_route` 的**地点名→坐标**解析可工作，且解析不到时如实澄清 | `PlaceResolverTest` + `PlanRouteToolTest`；未命中返回 `place_not_found` |

### 2.2 非目标

见 §9 YAGNI。

---

## 3. 架构

```
用户提问（/api/deepseek/chat，仅 TEACHER/ADMIN）
   │
   ▼
AgentOrchestrator.run(question, sessionId, userId, ip)
   │  turnId = UUID（一次提问的唯一标识）
   │
   ├─ 构建 messages：[system(工具使用纪律)] + 历史(3轮) + [user]
   │
   └─ 循环 step = 1..maxSteps(5)
        │
        ├─→ DeepSeekClient.chat(messages, tools, tool_choice=auto)
        │        │
        │        ├─ tool_calls 为空 ──→ 最终答案，stopReason=final_answer
        │        │
        │        └─ tool_calls 非空 ──→ 逐个处理：
        │                 │
        │                 ├─ ToolRegistry.get(name)
        │                 │     ├─ 不存在 ──→ 回填 {"error":"unknown_tool","available":[...]}
        │                 │     └─ 存在  ──→ 解析 arguments（JSON 字符串）
        │                 │                    ├─ JSON 解析失败 ──→ 回填 {"error":"invalid_arguments"}
        │                 │                    ├─ schema 校验失败 ──→ 回填 {"error":"invalid_arguments"}
        │                 │                    └─ 合法 ──→ execute() ──→ 结果截断/摘要
        │                 │
        │                 ├─ 落 agent_tool_call_log（turn_id, step, tool, args, digest, ms, status）
        │                 └─ messages += [assistant(tool_calls), tool(call_id, result)]
        │
        ▼
   AgentAnswer{answer, steps[], citations[], stopReason, totalMs, degraded}
        │
        ├─→ ai_chat_history（**现有表，字段不变**，见 §3.4）
        └─→ agent_tool_call_log（每步一行）
```

### 3.1 工具清单

| 工具名 | 入参（JSON Schema） | 底层实现 | 用途 |
|---|---|---|---|
| `search_knowledge` | `query: string (required)`, `top_k: integer (1-10, default 3)` | **`com.freshman.rag.HybridRetriever`**（子系统 A 产出） | 非结构化知识问答，返回带来源的材料 |
| `query_campus_data` | `entity: enum{dormitory, cafeteria, club, building, major, registration_step} (required)`, `filters: object (可选，字段白名单)` | MyBatis-Plus 白名单只读查询 | 结构化事实查询（"厚德学区物业费多少"） |
| `plan_route` | `from: string (required)`, `to: string (required)` | `PlaceResolver` + `BaiduNavigationService`（见 §3.2） | "从宿舍到食堂怎么走" |
| `get_current_time` | 无参 | JDK `LocalDateTime` | 时间类问题 |
| `calculate` | `expression: string (required)` | 受限表达式求值（**仅 `+ - * / ( ) 数字` 的字符白名单**，不用脚本引擎） | 算术，避免模型心算出错 |

> A2 的"4 类"= 知识检索 / 结构化查询 / 路线 / 通用工具（后两个工具合为一类）。

**`query_campus_data` 安全设计（必被追问）**：

1. `entity` 是**枚举**，映射到固定 Service/Mapper 方法与输出字段白名单，**不存在自由 SQL 拼接路径**
2. `filters` 的键必须在该 entity 字段白名单内，值一律走 MyBatis-Plus 参数化条件（`LambdaQueryWrapper.eq`）；注入尝试（如 `{"name":"' OR 1=1 --"}`）只会被当作普通字符串值匹配，匹配不到数据
3. 结果行数上限 20，单字段长度截断 500 字，整体结果序列化后 ≤ 4000 字
4. 未注册 entity 抛业务异常 → 编排层捕获并回填为 `tool` 错误消息
5. **输出字段白名单不含任何个人信息**（`guide_teacher` 整体不开放）

### 3.2 `plan_route` 的地点解析方案（修订重点）

**问题**：现有 `getWalkingRoute(double fromLat, double fromLng, double toLat, double toLng, String toName)`（`BaiduNavigationService.java:70-72`）**只接受坐标**，且请求把起点硬编码为 `"从我的位置步行到{toName}"`（`:82-86`）。而项目**没有任何地理编码能力**，`life_dormitory` 没有坐标字段，`life_cafeteria` 只有描述文本，`campus_building`（唯一带坐标的表）里**没有宿舍区和食堂**。若照原设计"直接复用"，`plan_route(from: string, to: string)` 无法实现。

**方案（三部分）**：

**(1) 复用现有坐标基础设施：把演示地点补进 `campus_building`**

不新增表、不新增列 —— `campus_building` 本就是"带坐标的可导航地点"，向它追加约 8 行种子数据即可，同时校园导览页也受益：

| 追加行 | name | 坐标来源 |
|---|---|---|
| 厚德学区（H1-H4） | `厚德学区` | **M1 用百度坐标拾取器核定后填入** |
| 博文学区（B1-B8） | `博文学区` | 同上 |
| 启智学区（Q1-Q6） | `启智学区` | 同上 |
| 第一食堂 / 第二食堂 | `第一食堂`、`第二食堂` | 同上 |
| 大庆站 / 大庆东站 / 大庆西站 | 同名 | 同上（**若无法核定则从 §7 场景中移除该地点**，不得编造坐标） |

> ⚠️ **坐标不得凭空编造**。M1 的第一项数据任务就是用百度坐标拾取器（或可信地图源）核定这 8 个坐标；核定不出来的地点不写入种子数据，且 §7 中依赖它的场景必须替换为可解析地点。验收由 `PlaceResolverTest` 断言"§7 每个场景出现的地点名均可解析"。

**(2) 新增 `PlaceResolver`（`com.freshman.agent.PlaceResolver`）**

```java
Optional<Place> resolve(String name);          // 单值解析，歧义时返回空 + 候选列表
List<Place>    resolveCandidates(String name); // 供澄清用
// Place: { Long buildingId, String name, double lat, double lng, String matchedBy }
```

- 匹配策略：名称精确匹配 → 去后缀归一化匹配（剥离"东北石油大学""校区""楼"等）→ `keywords` 字段包含匹配
- 候选 > 1（歧义，如"食堂"）→ 返回 `place_ambiguous` + 候选列表，让**模型向用户澄清**，不擅自选一个
- 命中 0 → 返回 `place_not_found`
- **降级**：解析失败时**不**回退到 Haversine 直线估算（那会给出看似合理实则错误的结果），而是如实告知

**(3) `BaiduNavigationService` 新增重载（保留原方法）**

```java
// 新增：允许指定起点名称，修正"从我的位置"的错误文案
public RouteResult getWalkingRoute(double fromLat, double fromLng, String fromName,
                                   double toLat, double toLng, String toName);
```

- 内部把 `user_raw_request` 由 `"从我的位置步行到{toName}"` 改为 `"从{fromName}步行到{toName}"`
- **原 5 参方法保留不动**，`NavigationController` 的现有调用零影响
- 现有三级容灾（真实路线 → 地点澄清 → Haversine 直线）保持不变，`plan_route` 工具在此之上再加一层"地点解析失败"的前置错误

`plan_route` 工具的返回：成功时给结构化路线（距离/时间/起终点名）；`place_not_found` / `place_ambiguous` 时给结构化错误 + 可用地点提示，由模型组织成澄清话术。

### 3.3 工具契约

```java
public interface AgentTool {
    String name();
    String description();
    Map<String, Object> parameters();           // JSON Schema（Hutool JSONObject 手工构造）
    ToolResult execute(Map<String, Object> args, ToolContext ctx);
}
// ToolResult: { boolean success, String content, Map<String,Object> meta }
// ToolContext: { String sessionId, Long userId, String ipAddress }
```

`ToolRegistry`：

- 构造注入 `List<AgentTool>`（Spring 自动收集实现 Bean）
- 启动校验：**重名检测**（冲突启动失败）、`name` 匹配 `^[a-z_]{3,40}$`、`parameters()` 含 `type/properties`
- 生成 OpenAI 兼容数组：`[{"type":"function","function":{"name","description","parameters"}}]`
- `get(name)` 未命中返回 `Optional.empty()`

### 3.4 编排循环与边界情况（本设计最核心部分）

```java
AgentAnswer run(String question, String sessionId, Long userId, String ip);
```

`maxSteps` 默认 5。**八个必须处理的边界情况**（与 §6 测试一一对应）：

| # | 边界情况 | 处理方式 | 对应测试场景 |
|---|---|---|---|
| 1 | **幻觉工具名** | `get()` 未命中 → 回填 `{"error":"unknown_tool","available":[...]}`，不抛异常 | 场景 3 |
| 2 | **schema 违规**（缺必填/类型错/枚举越界） | 回填 `{"error":"invalid_arguments","detail":...}` | 场景 4 |
| 3 | **`arguments` 不是合法 JSON**（模型偶发输出截断/多余文字；`arguments` 是**字符串**需二次解析） | 与 schema 违规**走同一回填路径** | 场景 5 |
| 4 | **并行 tool_calls** | 逐个执行并逐个回填，每条 `tool` 消息的 `tool_call_id` 与 assistant 消息中的 id **精确一致且顺序保持**（否则下一轮 API 返回 400） | 场景 2 |
| 5 | **工具结果过长** | 单工具结果 > `tool-result-max-chars`(4000) 则截断并附 `…[已截断，完整结果 N 字]` | 场景 8 |
| 6 | `reasoning_content` 与 tool_calls 共存 | **不回填进 messages**（会污染后续轮次），仅记日志 | 场景 6 |
| 7 | **步数上限** | 达到 `maxSteps` → 停止，返回 `stopReason=max_steps` + 中间结果摘要 | 场景 7 |
| 8 | **token 预算** | 累计 `usage.total_tokens` 超 `token-budget`(30000) → 停止，`stopReason=budget_exhausted` | 场景 7（同测） |

**`stopReason` 枚举（完整）**：`final_answer`（正常成功）/ `max_steps` / `budget_exhausted` / `degraded`（无工具降级路径返回）。

其他要求：

- 每步请求超时 30s；单步失败记 `status=error` 并回填错误，允许模型换路径
- 最终答案必须来自最后一条无 `tool_calls` 的响应；循环结束仍无最终答案时，用已收集的工具结果拼装兜底答案并标注 `stopReason`
- **所有工具只读**（A8）

### 3.5 轨迹与持久化（修订：不再假设 `ai_chat_history` 有 `category` 列）

> **事实核对**：`ai_chat_history` 的实际列是 `id/user_id/session_id/question/answer/source_knowledge_id/confidence/is_unknown/ip_address/create_time`（`freshman_orientation.sql:22-38`），**没有 `category` 列**；`category` 只存在于内存 DTO `AiQaService.ChatResponse`（`AiQaService.java:38`），从不落库（`DeepSeekChatService.java:339-354`）。
> **决定**：**不改动 `ai_chat_history` 表结构**（与子系统 A 的"不修改现有表"口径一致），也不写不存在的列。最终答案按现有 `saveHistory` 行为落库；**Agent 身份由新表承载**。

新表（脚本 `docs/sql/2026-09-18_agent_schema.sql`，幂等）：

```sql
CREATE TABLE IF NOT EXISTS `agent_tool_call_log` (
  `id`            bigint        NOT NULL AUTO_INCREMENT,
  `turn_id`       varchar(64)   NOT NULL COMMENT '一次提问的唯一标识(UUID)，同一次提问的多步共享',
  `session_id`    varchar(64)   DEFAULT NULL,
  `step_no`       int           NOT NULL COMMENT '第几步',
  `tool_name`     varchar(50)   NOT NULL,
  `arguments`     varchar(1000) DEFAULT NULL COMMENT '模型给出的原始参数 JSON 字符串',
  `result_digest` varchar(1000) DEFAULT NULL COMMENT '结果摘要（截断后）',
  `duration_ms`   int           DEFAULT NULL,
  `status`        varchar(20)   NOT NULL COMMENT 'success/error/invalid_arguments/unknown_tool',
  `error`         varchar(500)  DEFAULT NULL,
  `create_time`   datetime      DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_turn` (`turn_id`),
  KEY `idx_session` (`session_id`),
  KEY `idx_tool` (`tool_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Agent 工具调用轨迹表';
```

- 一次提问生成一个 `turnId`（UUID），随响应返回给前端；`GET /api/deepseek/trace?turnId=xxx` 回放轨迹
- **"这轮是否用了工具"** 的判定：`SELECT COUNT(*) FROM agent_tool_call_log WHERE turn_id=?`（不依赖 `ai_chat_history` 的改动）。历史回显**不加** Agent 角标（YAGNI：历史接口不返回 turnId，为此改表结构不值得）
- 需要新增 `AgentToolCallLog` 实体与 `AgentToolCallLogMapper`，Mapper **必须放在 `com.freshman.mapper`**（`FreshmanApplication.java:24` 的 `@MapperScan` 只扫该包）

### 3.6 降级与护栏

| 场景 | 行为 |
|---|---|
| 未配置 apiKey / `enabled=false` | 复用现有配置引导。**实现方式**：把 `DeepSeekChatService.chat()` 中 `:119-136` 的分支**重构抽出为 `configGuideResponse(question)` 方法**，`chat()` 与新编排层共用；`chat()` 保留为无工具路径，行为不变 |
| DeepSeek 不可用/超时 | 降级为**无工具普通 chat**（即现有 `chat()` 行为），`degraded=true` |
| 模型不支持 `tools`（返回 400 且报文含 tools 相关错误） | 自动降级为无工具模式并记录，不重试死循环 |
| 多步循环异常 | 捕获后返回已完成步骤摘要 + 明确说明 |

护栏参数：`maxSteps=5`、`token-budget=30000`、单步超时 30s、`search_knowledge.top_k ≤ 10`、工具结果 ≤ 4000 字。

### 3.7 前端 `deepseek-chat.html`

- 每条回答下方「🔧 工具调用轨迹」折叠卡片，每步一行：
  `① search_knowledge({"query":"宿舍空调"}) → 命中 3 条 · 320ms`；展开看结果摘要；`status=error` 标红
- `search_knowledge` 返回的来源渲染为可点击来源列表（用 `ScoredChunk` 的 `title`/`urlPath`/`snippet`）
- 页头显示模式：`Agent 模式（5 个工具可用）` / `普通对话模式（未启用工具）`
- `place_not_found` / `place_ambiguous` 时，把可用地点候选渲染为可点击快捷回复

---

## 4. 配置项

```yaml
app:
  ai:
    deepseek:
      # 现有项保持
      enabled: true
      apiKey: "${DEEPSEEK_API_KEY:}"
      apiUrl: "https://api.deepseek.com/v1/chat/completions"
      model: "deepseek-v4-flash"
      useRag: false          # Agent 模式下由 search_knowledge 工具按需检索，此项仅为兼容保留
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

- `agent.enabled=false` 时行为与改造前完全一致（可回退）
- **注册方式**：`AgentProperties` 写成 `@Component + @ConfigurationProperties(prefix = "app.ai.deepseek.agent")`（项目无 `@ConfigurationPropertiesScan`）

---

## 5. 接口

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/deepseek-chat` | TEACHER/ADMIN | 页面，新增模式标识与轨迹区 |
| POST | `/api/deepseek/chat` | TEACHER/ADMIN | **响应结构向后兼容**，新增 `steps[]` / `stopReason` / `turnId` / `degraded` |
| GET | `/api/deepseek/status` | TEACHER/ADMIN | 新增 `agentEnabled` / `tools[]` |
| GET | `/api/deepseek/history` | TEACHER/ADMIN | 不变 |
| GET | `/api/deepseek/trace` | TEACHER/ADMIN | 新增，按 `turnId` 回放轨迹 |

权限保持现状（`SecurityConfig.java:71-72`）。

---

## 6. 测试计划

> **测试基建从零建立**：`src/test` 不存在，M1 首个任务是创建目录结构。
> **必须补 surefire 配置**：`pom.xml` 无 surefire 配置，仅 `@Tag("eval")` **不会排除任何测试**，付费场景会在 `mvn test` 时真实调用 API。需加插件配置 `<excludedGroups>eval</excludedGroups>`（仅插件配置，**不新增依赖，A10 成立**）。
> 所有非 eval 测试必须是**纯单元测试**（不启动 Spring 上下文、不连 MySQL），否则 DoD 的「`mvn test` 全绿」不可达。

| 测试类 | 覆盖 | 花钱 |
|---|---|---|
| `ToolRegistryTest` | schema 生成正确、重名检测触发启动失败、非法 name 拒绝 | 否 |
| `QueryCampusDataToolTest` | 拒未知 entity、行数上限 20、字段截断、**注入尝试被当普通值** | 否 |
| `PlaceResolverTest` | `campus_building` 精确/归一化/关键词命中、歧义返回候选、未命中返回空、**§7 场景地点全部可解析** | 否 |
| `PlanRouteToolTest` | 解析成功走真实路线、`place_not_found`、`place_ambiguous`、底层三级容灾 | 否 |
| `CalculateToolTest` | 合法表达式、含字母/分号/脚本的表达式被拒 | 否 |
| `SearchKnowledgeToolTest` | `top_k` 越界钳制、空结果返回可读提示、来源字段映射自 `ScoredChunk` | 否 |
| `AgentOrchestratorTest` | **脚本化 LLM 响应重放，8 个场景**：①单工具 ②并行双工具 ③幻觉工具名自愈 ④schema 违规 ⑤`arguments` 非合法 JSON ⑥`reasoning_content` 不污染 ⑦maxSteps + token 预算截断 ⑧工具结果 >4000 字截断 | 否 |
| `AgentDemoScenariosTest` | §7 的 5 个场景真实调用，`@Tag("eval")`（被 surefire 排除，手动跑） | 是 |

**脚本化重放**是测试核心：`DeepSeekClient` 抽象为接口，测试注入 `ScriptedDeepSeekClient`（按预设序列返回 tool_calls → 最终答案），从而**在不消耗 API 额度的前提下验证整个循环的所有分支**。

集成验证（我本地执行）：`mvn test` 全绿 + 启动应用后按 §7 场景逐个验证（真实 API，费用可控：5 场景 × 约 3 步；**注意本环境只能用 Java 发 HTTPS**，`curl` 仅用于 localhost 明文）。

---

## 7. 演示用例 = 验收标准

| # | 提问 | 期望调用序列 | 坐标依赖（M1 核定） | 验证目标 |
|---|---|---|---|---|
| 1 | 厚德学区宿舍多少钱一年？有空调吗？从那儿走到第一食堂多远？ | `query_campus_data(dormitory)` → `plan_route` | 厚德学区、第一食堂 | 跨工具、多步 |
| 2 | 报到要带什么材料？从大庆站怎么到学校？ | `search_knowledge` → `plan_route` | 大庆站、学校正门（已有坐标） | RAG 接缝 + 工具；**若大庆站坐标无法核定，改用"从学校正门"** |
| 3 | 军训服不合身怎么办？ | `search_knowledge`（单步） | 无 | 基础路径 |
| 4 | 你们学校食堂好吃吗？ | `search_knowledge` → 空结果 → 如实说明 | 无 | 不编造 |
| 5 | 今天几号？军训 14 天的话结束是哪天？ | `get_current_time` → `calculate` | 无 | 工具优于心算 |

答辩话术要点：**同一问题，Agent 调了 2 个工具给出可溯源答案，纯 chat 只能凭记忆作答** —— 轨迹卡片就是证据。

---

## 8. 新增与修改文件

### 8.1 新增

```
com.freshman.agent
  AgentTool.java / ToolRegistry.java / ToolContext.java / ToolResult.java
  AgentOrchestrator.java
  AgentProperties.java            @Component + @ConfigurationProperties
  PlaceResolver.java              名称→坐标（§3.2）
  tool/SearchKnowledgeTool.java
  tool/QueryCampusDataTool.java
  tool/PlanRouteTool.java
  tool/UtilityTools.java          get_current_time + calculate
  dto/AgentStep.java / AgentAnswer.java
com.freshman.agent.llm
  DeepSeekClient.java             接口
  OpenAiCompatibleDeepSeekClient.java
com.freshman.entity.AgentToolCallLog.java
com.freshman.mapper.AgentToolCallLogMapper.java    必须在 com.freshman.mapper
src/test/java/com/freshman/agent/                  §6 的 8 个测试类 + ScriptedDeepSeekClient
docs/sql/2026-09-18_agent_schema.sql
```

### 8.2 修改

| 文件 | 修改内容 |
|---|---|
| `DeepSeekChatService.java` | 抽出 `configGuideResponse(question)`；`chat()` 保留为无工具路径 |
| `DeepSeekController.java` | `/api/deepseek/chat` 支持 Agent 路径；新增 `/api/deepseek/trace`；`/status` 增 `agentEnabled`/`tools[]` |
| `BaiduNavigationService.java` | **新增 6 参重载**（带 `fromName`），原 5 参方法不动 |
| `templates/deepseek-chat.html` | 轨迹卡片、模式标识、来源列表、地点候选快捷回复 |
| `src/main/resources/application.yml` | 新增 `app.ai.deepseek.agent.*`；`apiKey` 改环境变量兜底 |
| `pom.xml` | **仅**新增 surefire `<excludedGroups>eval</excludedGroups>` 插件配置 |
| `freshman_orientation.sql` | 追加约 8 行 `campus_building` 种子数据（§3.2，坐标为 M1 核定值） |

---

## 9. 明确不做（YAGNI）

| 不做 | 理由 |
|---|---|
| Plan-and-Execute / 任务规划 | 本次为工具型 Agent，规划由模型在循环内隐式完成 |
| 长期记忆 / 跨会话记忆 | 需额外存储与召回设计，与目标无关 |
| 多 Agent 协作 | 单人项目演示收益低、复杂度高 |
| Agent 写操作类工具（改数据、发通知） | 安全风险，与只读护栏冲突 |
| 引入 Spring AI / LangChain4j | 手写循环才能讲透；框架会掩盖 `tool_call_id` 对齐等关键机制 |
| 流式输出（SSE） | Agent 多步场景下收益低、改动大 |
| 接入第三方地理编码服务 | `campus_building` + 核定坐标已够演示；地理编码属后续增强 |
| 改 `ai_chat_history` 表结构 | 与子系统 A 口径一致；Agent 身份由 `agent_tool_call_log` 承载已足够 |
| 放开 `/deepseek-chat` 权限给新生 | 保持 TEACHER/ADMIN 现状 |
| 统一三处会话管理 | 独立技术债 |

---

## 10. 风险与缓解

| 风险 | 影响 | 缓解 |
|---|---|---|
| ~~DeepSeek 不支持 tools~~ | — | ✅ **已实测通过**（`finish_reason=tool_calls`），风险退役 |
| **8 个演示地点坐标无法核定** | §7 场景 1/2 无法演示 | M1 优先核定；核定不出的地点不进种子数据，场景改用可解析地点；`PlaceResolverTest` 强制断言 |
| 并行 tool_calls 的 `tool_call_id` 对齐错误 | 下一轮 API 400 | 单测专门覆盖；日志记录 messages 结构摘要 |
| 模型陷入工具循环 | 烧 token | `maxSteps` + `token-budget` 双保险；轨迹表可事后分析 |
| 真实演示成本 | 费用 | 单测全部脚本化重放；真实调用仅限 §7 的 5 个场景；surefire 排除 eval |
| 工具结果污染上下文 | 输出劣化 | 截断 + 摘要；`reasoning_content` 不回填 |
| 结构化查询泄露敏感字段 | 数据安全 | 输出字段白名单（不含手机号/邮箱/身份证）；`guide_teacher` 不开放 |
| **本环境 TLS 限制** | 验证手段受限 | 外部 API 验证走 Java；`curl` 仅 localhost 明文 |
| `src/test` 不存在 | 测试计划无法落地 | M1 首任务建立测试基建 |
| 依赖子系统 A 未完成 | 无法开始 | 顺序固定先 A 后 B；仅 `search_knowledge` 强依赖，其余 4 个工具可独立开发 |

---

## 11. 里程碑

| 里程碑 | 内容 | 完成判据 |
|---|---|---|
| **M0 前置验证** ✅ 已完成 | tools 支持、返回结构、构建链路 | 见 §0 |
| **M1 数据与工具层** | 测试基建 + surefire 配置；`campus_building` 8 行坐标核定；`PlaceResolver`；`AgentTool`/`ToolRegistry`；5 个工具；7 个工具类单测 | 7 个测试类全绿；`PlaceResolverTest` 断言 §7 地点全部可解析 |
| **M2 编排循环** | `DeepSeekClient` 抽象、`AgentOrchestrator`、8 个边界情况 | `AgentOrchestratorTest` 8 场景全绿 |
| **M3 轨迹与前端** | 实体/Mapper、`agent_tool_call_log`、trace 接口、轨迹卡片、模式标识 | 页面看到 2 步轨迹；`curl` 验证 trace |
| **M4 演示与收尾** | 5 场景实测、降级验证、文档与话术更新 | §7 全通过 + 断 key 降级不报错 |

---

## 12. 完成定义（DoD）

- [ ] `docs/sql/2026-09-18_agent_schema.sql` 含 `IF NOT EXISTS`，可幂等执行
- [ ] `pom.xml` 依赖列表未变（A10）；仅新增 surefire `excludedGroups`，且 `mvn test` **不会**触发付费场景
- [ ] §6 全部非 eval 测试通过；`AgentOrchestratorTest` 覆盖全部 8 个边界情况
- [ ] §7 的 5 个场景真实链路跑通，轨迹可在页面看到
- [ ] §2.1 的 A1–A11 逐条有证据
- [ ] `query_campus_data` 的注入尝试实测被拒
- [ ] `PlaceResolver` 对 §7 全部地点名解析成功，且 `place_not_found` 路径实测
- [ ] 断 key / `agent.enabled=false` 时行为与改造前一致（可回退）
- [ ] 全部工具只读（代码审查确认无写操作）
- [ ] **未修改 `ai_chat_history` 表结构**
