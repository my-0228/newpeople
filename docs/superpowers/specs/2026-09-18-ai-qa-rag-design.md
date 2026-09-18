# AI 智能问答模块 RAG 化设计

- 日期：2026-09-18
- 范围：子系统 A —— `AI 智能问答`（`/ai-chat`）改造为完整 RAG 项目
- 关联文档：`2026-09-18-deepseek-agent-design.md`（子系统 B，本设计是其前置依赖）
- 实施顺序：**本设计先做**。子系统 B 的 `search_knowledge` 工具将直接注入本设计产出的 `HybridRetriever`

---

## 1. 背景与现状诊断

### 1.1 代码事实

| 事实 | 位置 |
|---|---|
| 本地语义匹配引擎：N-gram 分词 + 词典正向最大匹配 | `AiQaServiceImpl.java:293` |
| 同义词扩展字典（反向索引） | `AiQaServiceImpl.java:98`、`:127` |
| 三策略融合评分：Jaccard 0.3 + 余弦 0.5 + 分类 0.2 | `AiQaServiceImpl.java:53-59`、`:453` |
| 未知问题阈值 0.25（硬编码常量） | `AiQaServiceImpl.java:50` |
| LLM 路径：`callLlmApi()` **无条件**做 RAG，无开关 | `AiQaServiceImpl.java:679-707` |
| RAG 检索门限 `score > 0.1`、TopK 硬编码 3 | `AiQaServiceImpl.java:693`、`:701` |
| 检索入口已抽象：`retrieveContext(question, topK)` | `AiQaService.java:102-110` |
| 知识库为「一问一答」结构，约 100 条 | `freshman_orientation.sql:65-78` |
| 长文本散落在 `guide_*` / `life_*` / `campus_*` / `sys_news` 表 | `freshman_orientation.sql:188-608` |

### 1.2 已知缺陷（本设计要修掉的靶子）

以下 5 条来自项目自身的面试答辩文档，属于**已承认**的问题，因此是本设计的验收目标而非新发现：

1. **检索门限形同虚设**：分类底分 0.06 + `priority*0.01` + 字符 N-gram 假重叠，导致"几乎任何问题都能凑满 Top3"，宽松的垃圾材料比没有材料更危险（`面试防守稿-自圆其说.md:399`、`:509`）
2. **无引用溯源**：用户无法验证答案来自哪条知识
3. **无评估集**：效果不可量化，"我觉得效果不错"无法抗追问
4. **核心算法参数硬编码**：阈值/权重/门限改一个都要重新编译部署（`面试防守稿-自圆其说.md:868`）
5. **可观测缺失**：未知问题统计有表无页面，召回质量无从分析

### 1.3 "完整 RAG" 的定义（本设计的判据）

> RAG = 语料切分与索引 → 向量召回 → 混合融合 → 生成 → **引用溯源** → **拒答兜底** → **量化评估**。
> 缺任一环，只能叫"LLM + 检索"，不能叫 RAG。

---

## 2. 目标与非目标

### 2.1 目标（全部可测，作为验收标准）

| 编号 | 目标 | 判定方式 |
|---|---|---|
| G1 | 知识来源从「仅 Q/A 表」扩展到长文档，完成切分并落库 | `kb_chunk` 中有非 `ai_knowledge` 来源的 chunk，且 chunk 长度分布符合配置 |
| G2 | 问题经 Embedding 后可在 **chunk 级**做向量检索 | `VectorIndex.search()` 返回带相似度的 chunk 列表 |
| G3 | 向量 + 关键词**双路召回**，RRF 融合 | `HybridRetrieverTest` 验证融合排序与去重 |
| G4 | **双门限拒答**：无合格材料时**不调用 LLM**，直接拒答 | 单测断言 LLM 客户端调用次数 = 0 |
| G5 | 答案带 `[n]` 引用编号，可溯源到来源页面 | 响应含 `citations[]`；前端可点击跳转 |
| G6 | 24 条评估集，产出 `Hit@1` / `Hit@3` / `MRR` / 拒答正确率 / 引用准确率 | `/admin/ai/eval` 输出 Markdown 对比表（改造前 vs 改造后） |
| G7 | 全流程可观测：召回候选、分数、分段耗时落库 | `ai_retrieval_log` 有记录，含 embedding/检索/生成三段耗时 |
| G8 | **离线降级**：LLM 或 Embedding 不可用时回退本地引擎 | 断开 API key 后 `/ai-chat` 仍返回本地匹配答案，标注降级 |
| G9 | 零新增 Maven 依赖 | `pom.xml` 的 `<dependencies>` 不变 |

### 2.2 非目标

见 §9 YAGNI 清单。

---

## 3. 架构与数据流

```
用户提问
   │
   ├─ 查询预处理：tokenize（复用现有分词）+ 同义词扩展
   │
   ├─【路径1 向量召回】EmbeddingClient.embed(question)
   │        └→ VectorIndex.search(cosine) ────────────→ Top20 (chunk)
   │
   └─【路径2 关键词召回】KeywordRetriever（复用现有 TF-IDF 评分）
            └────────────────────────────────────────→ Top20 (chunk)
   │
   ▼
HybridRetriever：RRF 融合 + 同文档相邻块去重 → 双门限过滤 → Top5
   │
   ├─ 无合格材料 ──→ 直接拒答（不调用 LLM，零成本零幻觉）
   │
   └─ 有材料 ──→ RagService 组装 prompt（材料带元信息）
                     │
                     ▼
              LlmClient（Qwen，OpenAI 兼容 /chat/completions）
                     │
                     ├─ 失败/超时 ──→ 降级：本地引擎最佳匹配 + degraded=true
                     ▼
              引用解析与校验（剔除幻觉引用号）
                     │
                     ▼
   ChatResponse{answer, citations[], confidence, unknown, degraded, costMs}
                     │
                     ├─→ ai_chat_history（现有表，保持 history 接口兼容）
                     └─→ ai_retrieval_log（新表，可观测）
```

---

## 4. 数据模型

新增 3 张表，**不修改任何现有表结构**。脚本：`docs/sql/2026-09-18_rag_schema.sql`

```sql
-- 知识文档：一个业务来源 = 一条文档
CREATE TABLE `kb_document` (
  `id`           bigint       NOT NULL AUTO_INCREMENT,
  `source_type`  varchar(50)  NOT NULL COMMENT '来源类型: ai_knowledge/guide_faq/guide_registration_step/guide_major/life_dormitory/life_cafeteria/life_club/campus_building',
  `source_id`    bigint       DEFAULT NULL COMMENT '来源业务表主键',
  `title`        varchar(300) NOT NULL COMMENT '文档标题，用于引用展示',
  `category`     varchar(50)  DEFAULT NULL COMMENT '分类，复用现有分类口径',
  `url_path`     varchar(200) DEFAULT NULL COMMENT '引用跳转路径，如 /guide/registration',
  `content_hash` char(64)     NOT NULL COMMENT '正文 SHA-256，用于增量重建时跳过未变更文档',
  `chunk_count`  int          DEFAULT 0,
  `status`       tinyint      DEFAULT 1 COMMENT '0-禁用 1-启用',
  `create_time`  datetime     DEFAULT CURRENT_TIMESTAMP,
  `update_time`  datetime     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_source` (`source_type`,`source_id`),
  KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='RAG 知识文档表';

-- 知识分块：检索的最小单位
CREATE TABLE `kb_chunk` (
  `id`              bigint      NOT NULL AUTO_INCREMENT,
  `document_id`     bigint      NOT NULL,
  `chunk_index`     int         NOT NULL COMMENT '文档内序号，从0开始',
  `content`         text        NOT NULL COMMENT '分块正文',
  `char_start`      int         DEFAULT NULL COMMENT '在原文中的起始偏移，用于溯源',
  `char_end`        int         DEFAULT NULL COMMENT '在原文中的结束偏移',
  `content_hash`    char(64)    NOT NULL COMMENT '分块正文 SHA-256，用于增量向量化',
  `embedding`       mediumtext  DEFAULT NULL COMMENT 'JSON float 数组，写入前已做 L2 归一化',
  `embedding_model` varchar(50) DEFAULT NULL COMMENT '如 text-embedding-v3',
  `dim`             int         DEFAULT NULL COMMENT '向量维度，如 1024',
  `status`          tinyint     DEFAULT 0 COMMENT '0-待向量化 1-已向量化 2-向量化失败',
  `create_time`     datetime    DEFAULT CURRENT_TIMESTAMP,
  `update_time`     datetime    DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_doc_chunk` (`document_id`,`chunk_index`),
  KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='RAG 知识分块表';

-- 检索日志：可观测性
CREATE TABLE `ai_retrieval_log` (
  `id`             bigint      NOT NULL AUTO_INCREMENT,
  `session_id`     varchar(64) DEFAULT NULL,
  `question`       varchar(500) NOT NULL,
  `vector_hits`    int         DEFAULT 0 COMMENT '向量召回命中数',
  `keyword_hits`   int         DEFAULT 0 COMMENT '关键词召回命中数',
  `final_chunk_ids` varchar(500) DEFAULT NULL COMMENT '最终进入 prompt 的 chunk id，逗号分隔',
  `top_score`      decimal(6,4) DEFAULT NULL COMMENT 'top1 融合分',
  `is_unknown`     tinyint     DEFAULT 0 COMMENT '是否拒答',
  `degraded`       tinyint     DEFAULT 0 COMMENT '是否降级回答',
  `embedding_ms`   int         DEFAULT NULL,
  `retrieval_ms`   int         DEFAULT NULL,
  `generate_ms`    int         DEFAULT NULL,
  `create_time`    datetime    DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_create_time` (`create_time`),
  KEY `idx_is_unknown` (`is_unknown`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='RAG 检索日志表';
```

### 4.1 关键设计决定：向量存 `MEDIUMTEXT`（JSON）而非 `BLOB float32`

| 方案 | 空间（1000 chunk × 1024 维） | 优点 | 缺点 |
|---|---|---|---|
| `MEDIUMTEXT` JSON（**选定**） | ≈ 10 MB | 可读、可直接 `SELECT` 出来演示、跨工具无二进制兼容问题 | 空间大、解析有开销 |
| `BLOB` float32 | ≈ 4 MB | 省 60% 空间 | 不可读、Demo 时无法展示 |

选 JSON 的理由：本项目规模下空间无意义（10 MB），但**面试现场能直接把向量 `SELECT` 出来给人看**，演示价值高于空间优化。规模上到十万级 chunk 时应迁 Milvus/pgvector，此时存储格式本身会被替换，JSON 的空间劣势不会成为瓶颈。

### 4.2 关键设计决定：`ai_knowledge` 的 Q/A 对**不切分**

`ai_knowledge` 一行本身就是「一个问题 + 一个答案」，语义边界天然完整，切分只会制造碎片。处理方式：

- 一行 = 一条 `kb_document`（`source_type='ai_knowledge'`）+ **恰好 1 条** `kb_chunk`
- chunk 正文 = `question + "\n" + answer`（问题与答案都要参与检索：问题侧提供语义入口，答案侧提供细节匹配）
- `keywords` / `synonyms` 字段**不拼进 chunk 正文**，改走关键词召回的独立通道（避免污染向量语义）

需要切分的是长文本来源：`guide_registration_step`、`guide_major`、`life_*`、`campus_building` 等。

---

## 5. 组件设计

### 5.1 `ChunkSplitter`（`com.freshman.rag.ChunkSplitter`）

```java
List<Chunk> split(String text, ChunkOptions opts);
// Chunk: { index, content, charStart, charEnd }
```

切分规则（按优先级依次执行）：

1. **结构感知**：先按标题/编号边界分段 —— 正则匹配 `^#{1,6}\s`、`^[一二三四五六七八九十]+、`、`^（[一二三四五六七八九十\d]+）`、`^\d+[.、]`、`^步骤\d+` ，以及连续空行（≥2）
2. **段内定长**：单段超过 `size` 时，按中文句末标点（`。！？；`）贪心拼接到 ≤ `size` 字
3. **overlap**：相邻 chunk 重叠 `overlap` 字，**回退到最近的句边界**，禁止从句中间截断
4. **碎片合并**：长度 < `min-size` 的 chunk 合并到前一个（首块则并入后一个）
5. **偏移记录**：`charStart` / `charEnd` 相对原文，供引用定位
6. **长度上限保护**：硬切兜底（单句仍超长时按 `size` 硬切），避免死循环

边界情况（必须有单测）：空文本、纯空白、超长无标点文本、仅标题无正文、`size < min-size` 的非法配置（启动时校验并拒绝）。

### 5.2 `EmbeddingClient`（`com.freshman.rag.EmbeddingClient`）

```java
float[] embed(String text);                  // 单条
List<float[]> embedBatch(List<String> texts); // 批量，内部按 batchSize 分组
```

- 协议：OpenAI 兼容 `POST {api-url}`，`{"model":"text-embedding-v3","input":[...],"dimensions":1024}`
- 实现：JDK `HttpURLConnection` + Hutool `JSONUtil`（**零新依赖**，与现有 `DeepSeekChatService` 风格一致）
- 批量：每请求 ≤ 25 条（`batch-size` 配置），多条时逐条校验返回顺序与 `index` 字段对齐
- 归一化：返回后立即做 L2 归一化，使后续点积等价于余弦相似度
- 重试：失败按指数退避重试 2 次（1s / 2s）
- **失败隔离**：仍失败的文本对应 chunk 标 `status=2`（失败），不阻塞同批其它 chunk，也不中断索引重建
- 超时：连接 10s / 读取 30s（与现有远程调用口径一致）

### 5.3 `VectorIndex`（`com.freshman.rag.VectorIndex`）

```java
void rebuild();                                  // 从 kb_chunk 全量重建
List<ScoredChunk> search(float[] query, int topK);
```

- 结构：`float[][] vectors` + `ChunkMeta[] metas`（平行数组），避免对象开销
- 加载时机：`ApplicationRunner` 启动时加载（**不阻塞启动**：加载失败只 `log.warn`，索引为空时检索自动退化为纯关键词召回）
- 检索：暴力余弦（向量已归一化 → 点积），结果用**固定大小最小堆**取 TopK（避免全排序）
- 热重建：`rebuild()` 在新数组上构建，完成后**原子替换 `volatile` 引用**，读路径无锁
- 规模说明（写进类注释）：1000 chunk × 1024 维 ≈ 100 万次乘加，实测 <5 ms；十万级以上需引入 HNSW/IVF

### 5.4 `KeywordRetriever`（`com.freshman.rag.KeywordRetriever`）

从 `AiQaServiceImpl` **原样抽出**（`tokenize` / `expandSynonyms` / `computeFusionScore` / `computeCosineSimilarity` / IDF 统计），算法一行不改，只把数据源从 `AiKnowledge` 泛化到 `kb_chunk`。

- 保留原有 `AiQaServiceImpl` 作为**降级引擎**（G8），两者共享同一份分词与同义词实现（抽到 `ChineseTokenizer` 工具类，避免重复实现 —— 修掉面试文档里"同一能力两套实现"的批评）
- IDF 语料改为基于 chunk 集合统计

### 5.5 `HybridRetriever`（`com.freshman.rag.HybridRetriever`）

```java
RetrievalResult retrieve(String question, int topK);
// RetrievalResult: { List<ScoredChunk> chunks, boolean hasQualifiedMaterial, int topScore, ... }
```

**RRF 融合**：`score(d) = Σ_paths 1 / (k + rank_path(d))`，`k = 60`（配置化）

- 选 RRF 而非加权求和的原因：无需为两条路径的不同分数量纲标定权重、对分数分布鲁棒、是工业界默认做法
- **同文档去重**：融合后同一 `document_id` 只保留得分最高的 2 条（避免 Top5 被同一段落的相邻 chunk 占满）

**双门限过滤**（修掉缺陷 1，本设计最关键的修复）：

| 门限 | 条件 | 说明 |
|---|---|---|
| 绝对门限 | 向量余弦 top1 ≥ `min-score`（默认 0.35） | 拦住"完全无关"的问题 |
| 相对门限 | 候选分 ≥ `relative-floor` × top1（默认 0.6） | 砍掉明显掉队的尾巴 |

两个门限都不过 → `hasQualifiedMaterial = false` → 直接拒答。

> 对纯关键词路径（无向量、降级场景）单独定义等效门限：沿用既有置信度阈值 `0.25`，并在日志中标注走了哪条门限，保证"拒答依据"可追溯。

### 5.6 `RagService`（`com.freshman.rag.RagService`）

```java
ChatResponse ask(ChatRequest req);   // 对外唯一入口
```

**Prompt 模板**（材料块带元信息，修复"参考材料无出处"问题）：

```
你是东北石油大学智慧迎新系统的迎新助手，专门为大一新生解答入学相关问题。

回答要求：
1. 只依据下面提供的参考材料回答，不要使用材料之外的学校具体信息
2. 每个论断后面必须标注引用编号，如 [1]、[2]
3. 如果参考材料不足以回答，必须明确说明"知识库暂未收录该问题"，并给出建议咨询渠道，禁止编造
4. 友好、简洁、用中文，可适当使用 emoji

参考材料：
【材料1｜来源：迎新指南-报到流程｜相似度：0.62】
<chunk 正文>

【材料2｜来源：AI知识库-宿舍有空调吗｜相似度：0.58】
<chunk 正文>
```

**引用处理**：

- 解析答案中所有 `[n]` → 映射到 `citations[]`（含 `documentId` / `title` / `urlPath` / `chunkId` / `snippet`）
- **幻觉引用检测**：答案引用了不存在的编号（如只有 3 条材料却出现 `[7]`）→ 从 `citations` 中剔除，记 `log.warn`，并把该次回答标记 `degraded=true`
- 未被任何 `[n]` 引用的材料照常返回（前端折叠展示，允许用户自查）

**拒答路径**：`hasQualifiedMaterial=false` → 返回 `unknown=true` 的固定话术 + `getQuickQuestions()` 推荐，**不调用 LLM**，`ai_chat_history.is_unknown=1`

**降级路径**：LLM 调用失败/超时/未配置 → 走 `KeywordRetriever` 的 top1，`degraded=true`，答案前缀标注"（离线降级回答）"

### 5.7 `KnowledgeIndexer`（`com.freshman.rag.KnowledgeIndexer`）

```java
IndexReport rebuildAll(boolean force);     // 全量/增量
```

流程：`抽取（每来源一个 DocumentSource 实现）` → `清洗（去 HTML 标签/折叠空白/去重）` → `切分` → `content_hash 比对（未变则跳过）` → `Embedding` → `落 kb_chunk` → `VectorIndex.rebuild()`

- 抽取来源（各一个实现类，统一接口 `DocumentSource`）：`ai_knowledge`、`guide_faq`、`guide_registration_step`、`guide_major`、`life_dormitory`、`life_cafeteria`、`life_club`、`campus_building`
- 每个来源必须提供 `title` + `url_path`（引用可跳转），缺失时降级为 `source_type + id` 展示
- 返回 `IndexReport{documentCount, chunkCount, embeddedCount, skippedCount, failedCount, costMs}`

### 5.8 `RagEvalRunner`（评估）

评估集：`docs/rag/eval-set.jsonl`，每行一条：

```json
{"id":1,"question":"宿舍有空调吗","expected_chunk_ids":[27],"answerable":true}
{"id":2,"question":"学校有没有游泳池","expected_chunk_ids":[],"answerable":false}
```

- 24 条，其中**至少 5 条 `answerable=false`**（必须拒答的题，验证门限真的在起作用）
- 指标定义：
  - `Hit@1` = top1 命中期望 chunk 的比例
  - `Hit@3` = top3 内命中期望 chunk 的比例
  - `MRR` = 期望 chunk 排名倒数均值
  - 拒答正确率 = `answerable=false` 被正确拒答的比例
  - 引用准确率 = 答案中的 `[n]` 全部指向真实材料的比例
- 载体：`RagEvalRunnerTest`（`@Tag("eval")`，默认跳过，需真实 API）+ `GET /admin/ai/eval` 输出 Markdown 表
- **必须产出改造前 vs 改造后对比表**并写入 `docs/rag/eval-report.md`

---

## 6. 配置项（全部外部化，修掉缺陷 4）

```yaml
app:
  ai:
    rag:
      enabled: true
      top-k-vector: 20          # 向量召回候选数
      top-k-keyword: 20         # 关键词召回候选数
      top-k-final: 5            # 进入 prompt 的材料数
      min-score: 0.35           # 绝对门限（向量余弦）
      relative-floor: 0.6       # 相对门限（× top1）
      keyword-min-score: 0.25   # 关键词路径等效门限（降级场景）
      rrf-k: 60                 # RRF 平滑常数
      max-per-document: 2       # 同文档最多保留 chunk 数
      chunk:
        size: 400
        overlap: 60
        min-size: 30
      embedding:
        api-url: "https://dashscope.aliyuncs.com/compatible-mode/v1/embeddings"
        api-key: "${DASHSCOPE_API_KEY:}"
        model: "text-embedding-v3"
        dimensions: 1024
        batch-size: 25
        connect-timeout: 10000
        read-timeout: 30000
```

启动时校验：`size > min-size > 0`、`overlap < size`、`relative-floor ∈ (0,1]`、`top-k-final ≤ top-k-*`。非法配置**启动即失败并给出明确报错**（配置错误不应表现为运行期诡异行为）。

---

## 7. 接口与前端

### 7.1 接口

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| POST | `/api/ai/chat` | 公开 | **签名与响应结构向后兼容** |
| GET | `/api/ai/history` | 公开 | 不变 |
| POST | `/admin/ai/reindex` | ADMIN | 触发索引重建，支持 `?force=true` |
| GET | `/admin/ai/eval` | ADMIN | 跑评估集，返回 Markdown 表 |
| GET | `/admin/ai/retrieval-stats` | ADMIN | 召回质量统计（拒答率、平均耗时、Top 未命中问题） |

`AiQaService.chat()` **签名不变**；`ChatResponse` 新增字段 `citations`（List）、`degraded`（Boolean）、`costMs`（Long），前端忽略新字段也不报错。

### 7.2 前端 `ai-chat.html`

- 答案下方「参考来源」折叠块：每条显示 `序号｜标题｜相似度`，点击跳 `url_path`
- 置信度可视化（现有 `confidence` 字段终于有用了）
- 拒答样式与普通回答区分（不同底色 + "知识库暂未收录"标签）
- 降级回答显示"离线降级"标签

---

## 8. 测试与评估计划

| 测试类 | 覆盖 |
|---|---|
| `ChunkSplitterTest` | 空文本/纯空白/超长无标点/仅标题/碎片合并/overlap 句边界/非法配置 |
| `EmbeddingClientTest` | 本地 HTTP stub：正常批量、返回乱序按 `index` 对齐、超时重试、单条失败隔离 |
| `VectorIndexTest` | 归一化后点积=余弦、TopK 最小堆正确性、热重建原子性 |
| `KeywordRetrieverTest` | 分词与同义词行为与改造前**逐例一致**（回归保护，防抽取时改坏算法） |
| `HybridRetrieverTest` | RRF 融合排序、同文档去重、双门限拒答、降级场景走关键词门限 |
| `RagServiceTest` | 无材料不调 LLM（断言调用次数 0）、引用解析、**幻觉引用剔除**、LLM 失败降级 |
| `KnowledgeIndexerTest` | 增量跳过（hash 未变不重复向量化）、失败 chunk 标 status=2 |
| `RagEvalRunnerTest` | `@Tag("eval")`，真实 API，产出指标 |

集成验证（我本地执行）：

1. 用缓存的 Maven 3.9.12（`-o` 离线）+ JBR 25（`-Dmaven.compiler.release=17`）执行 `mvn test`
2. 连本地 MySQL 3306 执行 schema 脚本，跑一次 `POST /admin/ai/reindex`，确认 chunk 与向量落库
3. 启动应用，用 `curl` 打 `/api/ai/chat`，验证：正常题带引用、无关题被拒答、断 key 走降级

---

## 9. 明确不做（YAGNI）

| 不做 | 理由 |
|---|---|
| 引入向量库（Milvus / pgvector / Redis） | 千级 chunk 下暴力检索 <5 ms，引入运维成本与依赖，收益为负 |
| Rerank 模型（gte-rerank 等） | 属"进阶档"，本次不引入；RRF + 双门限已能解决噪声污染问题 |
| 查询改写 / HyDE / 多查询 | 同上，且会成倍增加 API 成本 |
| 流式输出（SSE） | 与现有 Thymeleaf + `fetch` 架构改动面大，答辩演示收益低 |
| 知识库 CRUD 管理页面 | 只提供 reindex 端点；内容维护仍走 SQL/现有后台 |
| PDF/Word 文档解析、多模态 | 无此数据源需求 |
| 会话管理统一重构 | 属独立技术债，不在本次范围 |
| 权限模型调整 | AI 问答页保持公开 |
| BM25 替换 TF-IDF | 会改变关键词召回行为，破坏 G9 之外的回归基线；本次保留 TF-IDF 原算法 |

---

## 10. 风险与缓解

| 风险 | 影响 | 缓解 |
|---|---|---|
| 现有阿里 key 未开通 `text-embedding-v3` | 无法向量化，G2 阻塞 | **M0 第一步验证**；退路：换 `text-embedding-v2`（1536 维）或 `text-embedding-v1`；再不行则该 key 的 Embedding 与 Chat 分属不同服务，需单独申请 |
| Embedding 调用成本/限流 | 重建慢、超额 | `content_hash` 增量跳过 + 批量 25 条/请求 + 失败隔离不重试全量 |
| `application.yml` 中明文 API Key 已入库 | 安全 | 新配置改用 `${DASHSCOPE_API_KEY:}` 环境变量兜底；提醒用户轮换该 key |
| `JAVA_HOME` 当前为 JDK 8，与项目 Java 17 不匹配 | 无法构建 | 构建时显式设置 `JAVA_HOME` 指向可用 JDK；已确认有 JBR 25 可用 |
| JDK 25 运行 Spring Boot 3.2.5 可能不兼容（ASM/CGLIB） | 启动失败 | 优先 `-Dmaven.compiler.release=17` 编译；运行时若异常，改用其它 JDK 或仅以 `mvn test` + 真实 MySQL 验证（不依赖应用启动） |
| 暴力检索的规模上限 | 数据增长后变慢 | 类注释写明适用规模与迁移路径；`ai_retrieval_log.retrieval_ms` 提供实测依据 |
| 索引加载失败导致检索全空 | 功能静默退化 | 加载失败 `log.warn` + 自动退化为纯关键词召回 + 管理端点可查索引状态 |

---

## 11. 里程碑

| 里程碑 | 内容 | 完成判据 |
|---|---|---|
| **M0 前置验证** | 验证 embedding API 可用性、维度、批量上限；确认构建链路（JBR 25 + Maven 3.9.12 离线） | 一次成功的 embedding 调用 + 一次成功的 `mvn test` |
| **M1 数据层** | DDL 脚本、`ChunkSplitter`、`EmbeddingClient`、`VectorIndex`、`KnowledgeIndexer`、reindex 端点 | chunk 与向量落库；`ChunkSplitterTest` / `EmbeddingClientTest` / `VectorIndexTest` 全绿 |
| **M2 检索与生成** | `KeywordRetriever` 抽取、`HybridRetriever`、`RagService`、拒答与降级、`ChatResponse` 扩展 | `HybridRetrieverTest` / `RagServiceTest` 全绿；`curl` 验证三类响应 |
| **M3 评估与可观测** | 评估集、`RagEvalRunner`、`ai_retrieval_log`、管理端点 | `eval-report.md` 产出改造前后对比表 |
| **M4 前端与收尾** | `ai-chat.html` 来源折叠块/置信度/拒答样式；文档与话术更新 | 人工走查 4 个场景截图通过 |

---

## 12. 完成定义（DoD）

- [ ] `docs/sql/2026-09-18_rag_schema.sql` 可在现有库上幂等执行
- [ ] `pom.xml` 依赖列表未变（G9）
- [ ] §8 全部单测通过（含改造前后关键词检索回归一致）
- [ ] §2.1 的 G1–G9 逐条有证据（日志、截图或测试输出）
- [ ] `docs/rag/eval-report.md` 含改造前后 Hit@1/Hit@3/MRR/拒答正确率对比
- [ ] 断网/断 key 场景实测通过（降级不报错、不阻断启动）
- [ ] 拒答题实测**未调用 LLM**（由 `ai_retrieval_log.generate_ms IS NULL` 与日志共同佐证）
