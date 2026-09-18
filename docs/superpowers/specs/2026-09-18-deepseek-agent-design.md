# DeepSeek 问答模块 Agent 化设计

- 日期：2026-09-18
- 范围：子系统 B —— `DeepSeek 问答`（`/deepseek-chat`）改造为 Agent（工具调用型智能体）项目
- 关联文档：
  - `2026-09-18-ai-qa-rag-design.md`（前置依赖，本设计的 `search_knowledge` 工具注入其 `HybridRetriever`）
  - **`2026-09-18-build-verification-notes.md`（构建与验证环境事实源）**
- 实施顺序：**子系统 A 完成后开始**
- 修订记录：v3 —— v2 通过规格评审（无架构阻塞）；本版落实 4 项修订（响应 DTO 归属、种子数据下发路径、真实数据断言的分层、`keywords`→`tags`）与全部 advisory

---

## 0. 前置验证结论（已实测）

| 验证项 | 结果 |
|---|---|
| `deepseek-v4-flash` 是否支持 `tools` | ✅ **HTTP 200**，`finish_reason="tool_calls"`，产出 `query_campus_data("{\"entity\": \"dormitory\"}")` |
| 工具调用参数格式 | ✅ `function.arguments` 是 **JSON 字符串**，需二次解析 |
| `usage` 字段 | ✅ 返回 `total_tokens`（实测 358），可用于 token 预算 |
| 构建链路 | ✅ `BUILD SUCCESS`（59 源文件 / 4.6s，**JDK 21**） |
| TLS | ⚠️ `curl`/`.NET` 不可用；**Java 正常**。外部 API 走 Java，`curl` 仅 localhost |

构建命令与 4 个必须绕开的坑见 `2026-09-18-build-verification-notes.md`（**特别是：不能用 JDK 25，Spring Boot 3.2.5 的 Lombok 在 JDK 25 上静默失效**）。

---

## 1. 背景与现状诊断

### 1.1 代码事实

| 事实 | 位置 |
|---|---|
| DeepSeek 问答为**单次** Chat Completions 调用（`chat()` 跨 112-269，HTTP 调用在 196-260） | `DeepSeekChatService.java:112-269` |
| 请求体只含 `model/messages/temperature/thinking`，**无 `tools`** | `DeepSeekChatService.java:176-190` |
| 多轮上下文：按 `sessionId` 取最近 6 条成功问答 | `DeepSeekChatService.java:158-168` |
| 未配置 apiKey 的配置引导是 **`chat()` 内的分支，不是可复用方法** | `DeepSeekChatService.java:119-136` |
| `saveHistory` / `recentSuccessHistory` 是 **private** | `DeepSeekChatService.java:339`、`:281` |
| 错误码分级（401/402/429） | `DeepSeekChatService.java:237-251` |
| 控制器返回 `Result<AiQaService.ChatResponse>`，该 DTO **无** `steps/stopReason/turnId/degraded` | `DeepSeekController.java:75`、`:94-97`；`AiQaService.java:34-54` |
| 权限：仅 TEACHER / ADMIN | `SecurityConfig.java:71-72` |
| 路由方法**只接受坐标**，且起点硬编码"从我的位置" | `BaiduNavigationService.java:70-72`、`:82-86` |
| 项目中**不存在任何地理编码/地点搜索** | 全库 grep 地理编码/geocoding = 0 |
| `campus_building` 是全库**唯一带经纬度**的表（12 行，`AUTO_INCREMENT=49`） | `freshman_orientation.sql:188-221` |
| `campus_building` 的模糊匹配列叫 **`tags`**（不是 `keywords`） | `freshman_orientation.sql:199`；`Building.java:53` |
| `life_dormitory` **无地址/坐标字段**；`life_cafeteria.location` 只是文本 | `Dormitory.java`；`freshman_orientation.sql:512` |
| `ai_chat_history` **没有 `category` 列** | `freshman_orientation.sql:22-38` |
| Mapper 扫描限定 `com.freshman.mapper`；无 `@ConfigurationPropertiesScan` | `FreshmanApplication.java:24` |
| `pom.xml` **无 surefire 配置**；**`src/test` 不存在**；`docs/sql/` 不存在 | 需从零建立 |

### 1.2 现状定性

该项目当前**不存在任何 Agent 能力**：全项目 `grep -i "tools|tool_calls|function call"` 在 Java 代码中零命中；`BaiduNavigationService` 中的 "Agent Plan" 是百度侧接口命名，本系统只是调用方。

判断 Agent 的四个特征 —— **自主规划、工具调用、多步执行循环、依据观察结果决定下一步** —— 当前一个都不具备。

### 1.3 本次改造的额外收益（架构叙事）

改造前：两个问答页面是**同一能力的重复实现且行为不一致**（`AiQaServiceImpl.callLlmApi()` 无条件 RAG，`DeepSeekChatService` 受 `useRag` 控制且为 `false`）—— 项目面试文档中被点名的缺陷。

改造后：`/ai-chat` = **RAG 问答**（离线优先、可溯源、可拒答、有评估）；`/deepseek-chat` = **Agent 问答**（能调工具、多步推理、轨迹可查）。从"重复实现"变成"职责分离"。

---

## 2. 目标与非目标

### 2.1 目标（全部可测）

| 编号 | 目标 | 判定方式 |
|---|---|---|
| A1 | 实现 OpenAI 兼容的 **function-calling 循环** | 单测以脚本化 LLM 响应驱动，完成 ≥2 步工具调用并产出最终答案 |
| A2 | 提供 **4 类共 5 个真实工具** | 每个工具均有独立单测（`get_current_time` 与 `calculate` 同属 `UtilityToolsTest`） |
| A3 | 支持**并行 tool_calls** 与 `tool_call_id` 精确对齐 | `AgentOrchestratorTest` 场景② |
| A4 | **幻觉工具名自愈** | 回填 `{"error":"unknown_tool"}` 后循环继续，无异常 |
| A5 | **参数校验**：schema 违规 **与 JSON 解析失败**均回填错误而不执行 | `QueryCampusDataToolTest` + `AgentOrchestratorTest` 场景④⑤ |
| A6 | **循环上限与预算护栏**：`maxSteps=5` + token 预算 | 场景⑦ |
| A7 | **轨迹落库 + 前端展示** | `agent_tool_call_log` 有记录；页面渲染可折叠轨迹卡片 |
| A8 | **只读安全**：Agent 无法修改任何业务数据 | 所有工具无写操作（代码审查）；注入尝试被拒（it 层断言） |
| A9 | **降级不破坏现状**：DeepSeek 不可用时退化为无工具普通 chat | 断 key 后页面可用，标注"未启用工具" |
| A10 | 零新增 Maven 依赖 | `pom.xml` 的 `<dependencies>` 不变（仅新增 surefire 插件配置） |
| A11 | `plan_route` 的**地点名→坐标**解析可工作，解析不到时如实澄清 | `PlaceResolverTest`（单元）+ `PlaceResolverIntegrationTest`（it 层，真实数据） |

### 2.2 非目标

见 §9 YAGNI。

---

## 3. 架构

```
用户提问（/api/deepseek/chat，仅 TEACHER/ADMIN）
   │
   ▼
AgentOrchestrator.run(question, sessionId, userId, ip)
   │  turnId = UUID
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
        │                 ├─ ToolRegistry.get(name)
        │                 │     ├─ 不存在 ──→ 回填 {"error":"unknown_tool","available":[...]}
        │                 │     └─ 存在  ──→ 解析 arguments（JSON 字符串）
        │                 │                    ├─ JSON 解析失败 ──→ 回填 {"error":"invalid_arguments"}
        │                 │                    ├─ schema 校验失败 ──→ 回填 {"error":"invalid_arguments"}
        │                 │                    └─ 合法 ──→ execute() ──→ 结果截断/摘要
        │                 ├─ 落 agent_tool_call_log（turn_id, step, tool, args, digest, ms, status）
        │                 └─ messages += [assistant(tool_calls), tool(call_id, result)]
        │
        ▼
   AgentAnswer{answer, steps[], citations[], stopReason, totalMs, degraded}
        │
        ├─→ ai_chat_history（**现有表，字段不变**；由 DeepSeekChatService 落库，见 §3.5）
        └─→ agent_tool_call_log（每步一行）
```

### 3.1 工具清单

| 工具名 | 入参（JSON Schema） | 底层实现 | 用途 |
|---|---|---|---|
| `search_knowledge` | `query: string (required)`, `top_k: integer (1-10, default 3)` | **`com.freshman.rag.HybridRetriever`** | 非结构化知识问答，返回带来源的材料 |
| `query_campus_data` | `entity: enum{dormitory, cafeteria, club, building, major, registration_step} (required)`, `filters: object (可选，字段白名单见下)` | MyBatis-Plus 白名单只读查询 | 结构化事实查询 |
| `plan_route` | `from: string (required)`, `to: string (required)` | `PlaceResolver` + `BaiduNavigationService`（§3.2） | "从宿舍到食堂怎么走" |
| `get_current_time` | 无参 | JDK `LocalDateTime` | 时间类问题 |
| `calculate` | `expression: string (required)` | 受限表达式求值（**仅 `+ - * / ( ) 数字` 字符白名单**，不用脚本引擎） | 算术 |

> A2 的"4 类" = 知识检索 / 结构化查询 / 路线 / 通用工具（后者含两个工具）。

**跨规格契约**（类型已在子系统 A 的 §5.5.1 钉死）：
- `com.freshman.rag.HybridRetriever#retrieve(String question, int topK)` → `com.freshman.rag.dto.RetrievalResult`
- `RetrievalResult{ List<ScoredChunk> chunks, boolean hasQualifiedMaterial, String gateMode, double topScore }`
- 本工具只读 `ScoredChunk` 的 `title / urlPath / snippet / sourceType / sourceId` 构造材料；**不得读 `rrfScore` 当相似度**
- **"无材料"判定用 `hasQualifiedMaterial == false`**（不是 `chunks.isEmpty()`），二者语义不同
- `retrieve()` **尊重传入 `topK`**（上限 `top-k-vector`=20），故 `top_k=10` 真的返回 10 条

**`query_campus_data` 安全设计（必被追问）**：

1. `entity` 是**枚举**，映射到固定 Service/Mapper 与输出字段白名单，**不存在自由 SQL 拼接路径**
2. **`filters` 字段白名单（现枚举，键不在表内 → 直接返回 `invalid_arguments`）**：

| entity | 允许的 `filters` 键 |
|---|---|
| `dormitory` | `name`, `buildingNo`, `type`, `roomType`, `fee` |
| `cafeteria` | `name`, `location`, `floors` |
| `club` | `name`, `category`, `memberCount` |
| `building` | `name`, `category`, `address`, `floors` |
| `major` | `name`, `college`, `degree`, `duration` |
| `registration_step` | `stepNo`, `title` |

3. 白名单内的值一律走 MyBatis-Plus 参数化条件（`LambdaQueryWrapper.eq`）。**注入断言用白名单内的键**（如 `filters={"name":"' OR 1=1 --"}`）→ 期望结果是**参数化匹配不到任何行**（0 行），而不是 `invalid_arguments`
4. 结果行数上限 20，单字段截断 500 字，整体序列化后 ≤ 4000 字
5. **输出字段排除个人信息**：`club.president`、`club.contact` 不外泄；`guide_teacher` 整体不开放

### 3.2 `plan_route` 的地点解析方案

**问题**：`getWalkingRoute(double fromLat, double fromLng, double toLat, double toLng, String toName)`（`BaiduNavigationService.java:70-72`）**只接受坐标**，且请求把起点硬编码为 `"从我的位置步行到{toName}"`（`:82-86`）。项目**没有地理编码能力**，`life_dormitory` 无坐标，`life_cafeteria.location` 只是文本，`campus_building` 里也没有宿舍区和食堂。

**方案（三部分）**：

**(1) 把 5 个校园内部地点补进 `campus_building`（复用现有坐标基础设施）**

不新增表、不新增列。**只补校园内部地点**（避免车站等校外地点在 `/campus` 页被当作"校园建筑"展示）：

| 追加行（固定 id，幂等） | `name` | `category` | 坐标 |
|---|---|---|---|
| 50 | `厚德学区` | `宿舍楼` | **M1 用百度坐标拾取器核定** |
| 51 | `博文学区` | `宿舍楼` | 同上 |
| 52 | `启智学区` | `宿舍楼` | 同上 |
| 53 | `第一食堂` | `食堂` | 同上 |
| 54 | `第二食堂` | `食堂` | 同上 |

> ⚠️ **坐标不得凭空编造**。M1 第一项数据任务就是核定这 5 个坐标；核定不出来的地点不写入，且 §7 中依赖它的场景必须替换为已有坐标的地点（`学校正门` id=37、`图书馆` id=38 等 12 行已存在）。
> **联动规则**：§7 场景 1 与 §6 的 `PlaceResolverIntegrationTest` 断言必须**同时**随实际写入的种子集移动 —— 某地点核不出坐标时，既不写入种子，也要把场景 1 换成可解析的校园地点，并让 it 断言改为「对**实际写入的种子集**逐个断言可解析且坐标落在大庆合理范围内」，**不要硬编码 id 50/53**。
> **因此 `大庆站` 等校外地点不进本设计**（无坐标来源，且会污染 `/campus` 列表）。

**(2) 新增 `PlaceResolver`（`com.freshman.agent.PlaceResolver`）**

```java
Optional<Place> resolve(String name);
List<Place>     resolveCandidates(String name);
// Place: { Long buildingId, String name, double lat, double lng, String matchedBy }
```

- 匹配策略：`name` 精确匹配 → 去后缀归一化匹配（剥离"东北石油大学""校区""楼"等）→ **`tags` 字段**包含匹配（注意列名是 `tags`，不是 `keywords`）
- 候选 > 1（歧义）→ 返回 `place_ambiguous` + 候选列表，让**模型向用户澄清**
- 命中 0 → `place_not_found`
- **降级**：解析失败**不**回退到 Haversine 直线估算（那会给出看似合理实则错误的结果），如实告知

**(3) `BaiduNavigationService` 新增重载（保留原方法）**

```java
public RouteResult getWalkingRoute(double fromLat, double fromLng, String fromName,
                                   double toLat, double toLng, String toName);
```

- 内部把 `user_raw_request` 改为 `"从{fromName}步行到{toName}"`
- **原 5 参方法保留不动**，`NavigationController` 现有调用零影响
- 现有三级容灾（真实路线 → 地点澄清 → Haversine 直线）不变；`plan_route` 在其之上加一层"地点解析失败"的前置错误

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

- 构造注入 `List<AgentTool>`；**`UtilityToolsConfig` 用 `@Configuration` + 两个返回 `AgentTool` 的 `@Bean` 方法暴露**（而不是一个类实现两次接口），保证被 `List<AgentTool>` 正确收集（类名与 §8.1 的 `tool/UtilityToolsConfig.java` 一致；其测试类仍叫 `UtilityToolsTest`）
- 启动校验：**重名检测**（冲突启动失败）、`name` 匹配 `^[a-z_]{3,40}$`、`parameters()` 含 `type/properties`
- 生成 OpenAI 兼容数组 `[{"type":"function","function":{"name","description","parameters"}}]`
- `get(name)` 未命中返回 `Optional.empty()`

### 3.4 编排循环与边界情况

```java
AgentAnswer run(String question, String sessionId, Long userId, String ip);
```

**八个边界情况**（与 §6 场景一一对应）：

| # | 边界情况 | 处理 | 场景 |
|---|---|---|---|
| 1 | **幻觉工具名** | 回填 `{"error":"unknown_tool","available":[...]}`，不抛异常 | ③ |
| 2 | **schema 违规** | 回填 `{"error":"invalid_arguments"}` | ④ |
| 3 | **`arguments` 非法 JSON**（模型偶发截断/多余文字） | **同一回填路径** | ⑤ |
| 4 | **并行 tool_calls** | 逐个执行、逐个回填，`tool_call_id` **精确一致且顺序保持**（否则下一轮 400） | ② |
| 5 | **工具结果过长** | > `tool-result-max-chars`(4000) 截断 + `…[已截断，完整结果 N 字]` | ⑧ |
| 6 | `reasoning_content` 与 tool_calls 共存 | **不回填进 messages**（会污染后续轮次），仅记日志 | ⑥ |
| 7 | **步数上限** | `stopReason=max_steps` + 中间结果摘要 | ⑦ |
| 8 | **token 预算** | 累计 `usage.total_tokens` 超 `token-budget`(30000) → `stopReason=budget_exhausted` | ⑦（同测） |

**`stopReason` 枚举**：`final_answer`（成功）/ `max_steps` / `budget_exhausted` / `degraded`。

其他：每步超时 30s；单步失败记 `status=error` 并回填错误；最终答案必须来自最后一条无 `tool_calls` 的响应，否则用已收集的工具结果拼装兜底答案并标注 `stopReason`；**所有工具只读**。

### 3.5 响应 DTO、轨迹与持久化

> **事实核对**：`ai_chat_history` 的实际列是 `id/user_id/session_id/question/answer/source_knowledge_id/confidence/is_unknown/ip_address/create_time`（`freshman_orientation.sql:22-38`），**没有 `category`**。`ChatResponse` 只有 question/answer/confidence/category/isUnknown/relatedQuestions，**装不下** `steps/stopReason/turnId/degraded`。

**决定（v3 钉死）**：

1. **新增独立 DTO `com.freshman.agent.dto.AgentChatResponse`**，字段 = 现有响应字段（`question/answer/confidence/isUnknown/relatedQuestions/category`，**JSON 键名不变**，前端旧逻辑继续可用）+ 新增 `steps[]/stopReason/turnId/degraded` + **`citations[]`**（结构化来源 `{index, title, urlPath, snippet, sourceType, sourceId}`，供 §3.7 的来源列表渲染；**不复用 trace 的 `resultDigest` 字符串**）
2. `POST /api/deepseek/chat` 返回类型由 `Result<AiQaService.ChatResponse>` 改为 `Result<AgentChatResponse>`
   - ⚠️ **降级/配置引导路径的映射必须显式实现**：`DeepSeekChatService.chat()` 与 `configGuideResponse(question)` **仍返回 `AiQaService.ChatResponse`** —— 注意它**并非"只被子系统 A 使用"**（降级路径会复用它），`AiQaService.java` 本身不改。编排层必须把它**逐字段映射**为 `AgentChatResponse`：`question/answer/confidence/category/isUnknown/relatedQuestions` 直搬，`steps=[]`、`citations=[]`、`turnId=本轮UUID`、`stopReason="degraded"`、`degraded=true`。此即 A9 / DoD 的「断 key 降级」路径，由 `AgentOrchestratorTest` 场景⑨覆盖
3. **不改 `ai_chat_history` 表结构**；最终答案由 `DeepSeekChatService.saveHistory(...)` 按现有行为落库（**需把 `saveHistory` 的可见性由 `private` 改为 `public`**，`recentSuccessHistory` 同理由编排层复用），§8.2 已列出该变更
4. Agent 身份由新表承载：

```sql
CREATE TABLE IF NOT EXISTS `agent_tool_call_log` (
  `id`            bigint        NOT NULL AUTO_INCREMENT,
  `turn_id`       varchar(64)   NOT NULL COMMENT '一次提问的唯一标识(UUID)',
  `session_id`    varchar(64)   DEFAULT NULL,
  `step_no`       int           NOT NULL,
  `tool_name`     varchar(50)   NOT NULL,
  `arguments`     varchar(1000) DEFAULT NULL COMMENT '模型给出的原始参数 JSON 字符串',
  `result_digest` varchar(1000) DEFAULT NULL,
  `duration_ms`   int           DEFAULT NULL,
  `status`        varchar(20)   NOT NULL COMMENT 'success/error/invalid_arguments/unknown_tool/place_not_found/place_ambiguous',
  `error`         varchar(500)  DEFAULT NULL,
  `create_time`   datetime      DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_turn` (`turn_id`), KEY `idx_session` (`session_id`), KEY `idx_tool` (`tool_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Agent 工具调用轨迹表';
```

- 新增 `AgentToolCallLog` 实体 + `AgentToolCallLogMapper`，Mapper **必须在 `com.freshman.mapper`**（`@MapperScan` 只扫该包）
- "这轮是否用了工具"：`SELECT COUNT(*) FROM agent_tool_call_log WHERE turn_id=?`；历史回显**不加** Agent 角标（YAGNI）
- **`GET /api/deepseek/trace?turnId=xxx` 响应结构**：`Result<Map>`，`data = {"turnId": "...", "steps": [ {stepNo, toolName, arguments, resultDigest, durationMs, status, error, createTime} ]}`（按 `stepNo` 升序）

### 3.6 降级与护栏

| 场景 | 行为 |
|---|---|
| 未配置 apiKey / `enabled=false` | 把 `DeepSeekChatService.chat()` 中 `:119-136` 的分支**抽出为 `configGuideResponse(question)`**，`chat()` 与新编排层共用；`chat()` 保留为无工具路径，行为不变 |
| DeepSeek 不可用/超时 | 降级为**无工具普通 chat**（现有 `chat()` 行为），`degraded=true`，`stopReason=degraded` |
| 模型不支持 `tools` | 自动降级为无工具模式并记录，不重试死循环 |
| 多步循环异常 | 捕获后返回已完成步骤摘要 + 明确说明 |

护栏：`maxSteps=5`、`token-budget=30000`、单步超时 30s、`search_knowledge.top_k ≤ 10`、工具结果 ≤ 4000 字。

### 3.7 前端 `deepseek-chat.html`

- 每条回答下方「🔧 工具调用轨迹」折叠卡片：`① search_knowledge({"query":"宿舍空调"}) → 命中 3 条 · 320ms`；展开看摘要；`status != success` 标红
- `search_knowledge` 的来源渲染为可点击列表（`title`/`urlPath`/`snippet`）
- 页头模式标识：`Agent 模式（5 个工具可用）` / `普通对话模式（未启用工具）`
- `place_not_found` / `place_ambiguous` 时把可用地点候选渲染为可点击快捷回复

---

## 4. 配置项

```yaml
app:
  ai:
    deepseek:
      enabled: true
      apiKey: "${DEEPSEEK_API_KEY:}"
      apiUrl: "https://api.deepseek.com/v1/chat/completions"
      model: "deepseek-v4-flash"
      useRag: false          # Agent 下由 search_knowledge 工具按需检索，此项仅为兼容保留
      timeout: 30000
      maxTokens: 0
      agent:
        enabled: true
        max-steps: 5
        token-budget: 30000
        tool-result-max-chars: 4000
        history-rounds: 3
        trace-enabled: true
```

- `agent.enabled=false` 时行为与改造前完全一致（可回退）
- `AgentProperties` 写成 `@Component + @ConfigurationProperties(prefix = "app.ai.deepseek.agent")`（项目无 `@ConfigurationPropertiesScan`）

---

## 5. 接口

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/deepseek-chat` | TEACHER/ADMIN | 页面，新增模式标识与轨迹区 |
| POST | `/api/deepseek/chat` | TEACHER/ADMIN | 返回类型改为 `Result<AgentChatResponse>`（现有 JSON 键名不变，新增 4 个键） |
| GET | `/api/deepseek/status` | TEACHER/ADMIN | 新增 `agentEnabled` / `tools[]` |
| GET | `/api/deepseek/history` | TEACHER/ADMIN | 不变 |
| GET | `/api/deepseek/trace` | TEACHER/ADMIN | 新增，结构见 §3.5 |

---

## 6. 测试计划（三层）

> **测试基建从零建立**；**必须补 surefire 配置**（`pom.xml` 无任何 surefire 配置，仅 `@Tag` 不排除任何测试）：
> `<configuration><excludedGroups>${test.excludedGroups}</excludedGroups></configuration>`
> 并定义默认属性 `<test.excludedGroups>eval,it</test.excludedGroups>`。**必须用属性占位符而不是字面量** —— 否则 POM 配置会覆盖命令行 `-D`，`it`/`eval` 层将无法手动执行。三层命令：
>
> | 层 | 命令 |
> |---|---|
> | 单元（默认） | `mvn test` |
> | 集成（it） | `mvn test -Dtest.excludedGroups=eval` |
> | 全部（含付费 eval） | `mvn test -Dtest.excludedGroups=` |
>
> （**仅插件配置，不新增依赖，A10 成立**）

| 层 | Tag | 依赖 | `mvn test` 是否执行 |
|---|---|---|---|
| 单元测试 | 无 | 纯 JVM（Mockito） | ✅ |
| **集成测试** | `@Tag("it")` | **真实 MySQL** | ❌ 排除 |
| 付费演示 | `@Tag("eval")` | 真实 DeepSeek API | ❌ 排除 |

| 测试类 | 层 | 覆盖 |
|---|---|---|
| `ToolRegistryTest` | 单元 | schema 生成、重名检测触发启动失败、非法 name 拒绝 |
| `QueryCampusDataToolTest` | 单元 | 拒未知 entity、**`filters` 键不在白名单 → `invalid_arguments`**、行数上限、字段截断 |
| `PlaceResolverTest` | 单元 | 精确/归一化/`tags` 命中（mock mapper）、歧义返回候选、未命中返回空 |
| `PlanRouteToolTest` | 单元 | 解析成功走真实路线调用、`place_not_found`、`place_ambiguous`、底层三级容灾 |
| `UtilityToolsTest` | 单元 | **`get_current_time`** + `calculate`（合法表达式、含字母/分号/脚本被拒） |
| `SearchKnowledgeToolTest` | 单元 | `top_k` 越界钳制、`hasQualifiedMaterial=false` → 可读提示、来源字段映射自 `ScoredChunk` |
| `AgentOrchestratorTest` | 单元 | **脚本化重放 9 场景**：8 个边界情况 —— ①单工具 ②并行双工具 ③幻觉工具名自愈 ④schema 违规 ⑤`arguments` 非法 JSON ⑥`reasoning_content` 不污染 ⑦maxSteps + token 预算 ⑧结果 >4000 字截断；⑨**降级映射**（`chat()` 的 `AiQaService.ChatResponse` → `AgentChatResponse`，断言 `degraded=true`/`stopReason=degraded`/`steps=[]`） |
| `PlaceResolverIntegrationTest` | **it** | 真实 MySQL：**§7 全部地点名可解析**、坐标非空且落在大庆合理经纬度范围内（约束"不得编造坐标"） |
| `QueryCampusDataIntegrationTest` | **it** | 真实 MySQL：白名单内键的**注入尝试返回 0 行**（而非 `invalid_arguments`） |
| `AgentDemoScenariosTest` | **eval** | §7 的 5 个场景真实调用 |

**脚本化重放**是测试核心：`DeepSeekClient` 抽象为接口，测试注入 `ScriptedDeepSeekClient`（预设序列返回 tool_calls → 最终答案），**在不消耗 API 额度的前提下验证整个循环的所有分支**。

集成验证（我本地执行）：`mvn test` 单元层全绿 → 手动跑 `it` 层（真实 MySQL）→ 按 §7 场景验证真实链路（外部 API 走 Java，`curl` 仅 localhost）。

---

## 7. 演示用例 = 验收标准

| # | 提问 | 期望调用序列 | 数据依赖 | 验证目标 |
|---|---|---|---|---|
| 1 | 厚德学区宿舍多少钱一年？有空调吗？从那儿走到第一食堂多远？ | `query_campus_data(dormitory)` → `plan_route` | 厚德学区(id 50)、第一食堂(id 53) | 跨工具、多步 |
| 2 | 报到要带什么材料？从学校正门怎么走到图书馆？ | `search_knowledge` → `plan_route` | **仅用已有数据**（正门 id 37、图书馆 id 38） | RAG 接缝 + 工具，零新增数据依赖 |
| 3 | 军训服不合身怎么办？ | `search_knowledge`（单步） | 无 | 基础路径 |
| 4 | 你们学校食堂好吃吗？ | `search_knowledge` → `hasQualifiedMaterial=false` → 如实说明 | 无 | 不编造 |
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
  PlaceResolver.java
  tool/SearchKnowledgeTool.java / QueryCampusDataTool.java / PlanRouteTool.java
  tool/UtilityToolsConfig.java    @Configuration，两个 @Bean AgentTool
  dto/AgentStep.java / AgentAnswer.java / AgentChatResponse.java
com.freshman.agent.llm
  DeepSeekClient.java             接口
  OpenAiCompatibleDeepSeekClient.java
com.freshman.entity.AgentToolCallLog.java
com.freshman.mapper.AgentToolCallLogMapper.java  必须在 com.freshman.mapper
src/test/java/com/freshman/agent/                 §6 的 7 个单元测试类 + ScriptedDeepSeekClient
src/test/java/com/freshman/agent/it/              §6 的 2 个 it 测试类（真实 MySQL）
src/test/java/com/freshman/agent/eval/            §6 的 AgentDemoScenariosTest（@Tag("eval")，付费）
docs/sql/2026-09-18_agent_schema.sql             建表 + 5 行 campus_building 固定 id 幂等 INSERT
```

### 8.2 修改

| 文件 | 修改内容 |
|---|---|
| `DeepSeekChatService.java` | 抽出 `configGuideResponse(question)`；`saveHistory`/`recentSuccessHistory` 由 `private` 改为 `public`（供编排层复用）；`chat()` 保留为无工具路径 |
| `DeepSeekController.java` | `/api/deepseek/chat` 支持 Agent 路径并返回 `Result<AgentChatResponse>`；新增 `/api/deepseek/trace`；`/status` 增 `agentEnabled`/`tools[]` |
| `BaiduNavigationService.java` | **新增 6 参重载**（带 `fromName`），原 5 参方法不动 |
| `templates/deepseek-chat.html` | 轨迹卡片、模式标识、来源列表、地点候选快捷回复 |
| `src/main/resources/application.yml` | 新增 `app.ai.deepseek.agent.*`；`apiKey` 改环境变量兜底 |
| `pom.xml` | **仅**新增 surefire `<excludedGroups>${test.excludedGroups}</excludedGroups>` + 默认属性值 `eval,it` |

> **`freshman_orientation.sql` 不改**：该脚本以 `DROP TABLE IF EXISTS campus_building` 开头，是**重建脚本**，且项目没有 `data.sql`/`schema.sql`/启动初始化器。因此 5 行种子数据以**固定 id + `ON DUPLICATE KEY UPDATE`** 的幂等形式写进 `docs/sql/2026-09-18_agent_schema.sql`，可直接施加到**已存在的开发库**（`campus_building` 当前 `AUTO_INCREMENT=49`，故用 id 50–54）。

---

## 9. 明确不做（YAGNI）

| 不做 | 理由 |
|---|---|
| Plan-and-Execute / 任务规划 | 本次为工具型 Agent，规划由模型在循环内隐式完成 |
| 长期记忆 / 跨会话记忆 | 需额外存储与召回设计 |
| 多 Agent 协作 | 演示收益低、复杂度高 |
| Agent 写操作类工具 | 与只读护栏冲突 |
| 引入 Spring AI / LangChain4j | 手写循环才能讲透；框架会掩盖 `tool_call_id` 对齐等机制 |
| 流式输出（SSE） | Agent 多步场景收益低 |
| 接入第三方地理编码服务 | `campus_building` + 核定坐标已够演示 |
| **校外地点（大庆站等）纳入地点库** | 无坐标来源，且会污染 `/campus` 列表页 |
| 改 `ai_chat_history` 表结构 | Agent 身份由 `agent_tool_call_log` 承载已足够 |
| 放开 `/deepseek-chat` 权限给新生 | 保持现状 |
| 统一三处会话管理 | 独立技术债 |

---

## 10. 风险与缓解

| 风险 | 影响 | 缓解 |
|---|---|---|
| ~~DeepSeek 不支持 tools~~ | — | ✅ 已实测通过，风险退役 |
| **5 个校园地点坐标无法核定** | §7 场景 1 无法演示 | M1 优先核定；核不出就不写种子数据，场景 1 改用已有坐标地点；`it` 层断言坐标在合理经纬度范围内 |
| 并行 tool_calls 的 `tool_call_id` 对齐错误 | 下一轮 API 400 | 单测专门覆盖 |
| 模型陷入工具循环 | 烧 token | `maxSteps` + `token-budget`；轨迹表可事后分析 |
| 真实演示成本 | 费用 | 单测脚本化重放；真实调用仅 §7 的 5 场景；surefire 排除 `eval`/`it` |
| 工具结果污染上下文 | 输出劣化 | 截断 + 摘要；`reasoning_content` 不回填 |
| 结构化查询泄露敏感字段 | 数据安全 | 输出字段白名单（排除 `club.president`/`club.contact`）；`guide_teacher` 不开放 |
| JDK 版本用错（Lombok 失效）/ Maven 仓库只读 | 构建失败 | 见构建说明（固定 JDK 21 + `-Dmaven.repo.local`） |
| 依赖子系统 A 未完成 | 无法开始 | 顺序固定先 A 后 B；仅 `search_knowledge` 强依赖，其余 4 个工具可独立开发 |

---

## 11. 里程碑

| 里程碑 | 内容 | 完成判据 |
|---|---|---|
| **M0 前置验证** ✅ | tools 支持、构建链路 | 见 §0 |
| **M1 数据与工具层** | 测试基建 + surefire；5 个坐标核定与幂等种子；`PlaceResolver`；`AgentTool`/`ToolRegistry`；5 个工具 | **6 个工具层单元测试类**全绿 + 2 个 `it` 类通过 |
| **M2 编排循环** | `DeepSeekClient` 抽象、`AgentOrchestrator`、8 个边界情况 | `AgentOrchestratorTest` 8 场景全绿 |
| **M3 轨迹与前端** | 实体/Mapper、`agent_tool_call_log`、trace 接口、轨迹卡片、模式标识 | 页面看到 2 步轨迹；`curl` 验证 trace |
| **M4 演示与收尾** | 5 场景实测、降级验证、文档与话术更新 | §7 全通过 + 断 key 降级不报错 |

---

## 12. 完成定义（DoD）

- [ ] `docs/sql/2026-09-18_agent_schema.sql` 含 `IF NOT EXISTS` 与固定 id 的幂等 INSERT，可**重复执行**且可直接施加到现有开发库
- [ ] `pom.xml` 依赖列表未变（A10）；仅新增 surefire 配置，且 `mvn test` **不触发** `it`/`eval`
- [ ] §6 单元层全部通过；`AgentOrchestratorTest` 覆盖全部 8 个边界情况
- [ ] 2 个 `it` 层测试通过（真实 MySQL），其中 `PlaceResolverIntegrationTest` 断言 §7 全部地点可解析且坐标在合理范围内
- [ ] §7 的 5 个场景真实链路跑通，轨迹可在页面看到
- [ ] §2.1 的 A1–A11 逐条有证据
- [ ] 白名单内键的注入尝试实测返回 0 行（it 层）
- [ ] 断 key / `agent.enabled=false` 时行为与改造前一致（可回退）
- [ ] 全部工具只读（代码审查确认无写操作）
- [ ] **未修改 `ai_chat_history` 表结构与 `AiQaService.ChatResponse`**
