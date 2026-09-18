# AI 智能问答模块 RAG 化设计

- 日期：2026-09-18
- 范围：子系统 A —— `AI 智能问答`（`/ai-chat`）改造为完整 RAG 项目
- 关联文档：`2026-09-18-deepseek-agent-design.md`（子系统 B，本设计是其前置依赖）
- 实施顺序：**本设计先做**。子系统 B 的 `search_knowledge` 工具将直接注入本设计产出的 `HybridRetriever`
- 修订记录：v2 —— 按规格评审意见修订（门限语义、关键词打分、序列化、LLM 客户端、评估集稳定性、权限、来源映射）；并补充 M0 实测证据

---

## 0. M0 前置验证结论（已实测，不是假设）

| 验证项 | 结果 | 证据 |
|---|---|---|
| DashScope `text-embedding-v3` 可用性 | ✅ **HTTP 200**，`data[0].embedding` 长度 **1024** | 2 条输入返回 43578 字节响应 |
| 现有阿里 key 是否具备 Embedding 权限 | ✅ 具备，无需额外申请 | 同上 |
| DeepSeek `deepseek-v4-flash` 是否支持 `tools` | ✅ **HTTP 200**，`finish_reason="tool_calls"`，产出 `query_campus_data({"entity":"dormitory"})` | 见子系统 B 设计 §0 |
| 构建链路 | ✅ 编译通过（缓存 Maven 3.9.12 + JBR 25，`-Dmaven.compiler.release=17`） | `javac` exit 0 |
| **本环境 TLS 限制** | ⚠️ `schannel`/`.NET`/`curl.exe` 无法建立 TLS（`SEC_E_NO_CREDENTIALS`）；**Java 自带 SunJSSE 正常** | 故所有外部 API 验证必须走 Java；`curl` 仅用于 localhost 明文 |

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
| 该方法的**唯一调用方**是 DeepSeek 的 RAG 模式 | `DeepSeekChatService.java:142` |
| 现有 JSON 解析是单字段字符串扫描器，**不能解析数组** | `AiQaServiceImpl.extractJsonField()` |
| 知识库为「一问一答」结构，100 条 | `freshman_orientation.sql:65-78`（`AUTO_INCREMENT=101`） |
| 长文本散落在 `guide_*` / `life_*` / `campus_*` / `sys_news` 表 | `freshman_orientation.sql:188-608` |
| **`src/test` 目录当前不存在**（无任何单元测试） | 测试基建需从零建立 |

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
| G1 | 知识来源从「仅 Q/A 表」扩展到 §5.7 映射表中的 10 张表，完成切分并落库 | `kb_chunk` 中各 `source_type` 均有数据，chunk 长度分布符合配置 |
| G2 | 问题经 Embedding 后可在 **chunk 级**做向量检索 | `VectorIndex.search()` 返回带原始余弦的 chunk 列表 |
| G3 | 向量 + 关键词**双路召回**，RRF 融合 | `HybridRetrieverTest` 验证融合排序与同文档去重 |
| G4 | **双门限拒答**：无合格材料时**不调用 LLM** | `RagServiceTest` 断言 `RecordingLlmClient.callCount == 0` |
| G5 | 答案带 `[n]` 引用编号，可溯源到 `url_path` | 响应含 `citations[]`；前端可点击跳转 |
| G6 | 24 条评估集（稳定 ref 键），产出 `Hit@1`/`Hit@3`/`MRR`/拒答正确率/引用准确率 | `/admin/ai/eval` 输出 Markdown 对比表 |
| G7 | 全流程可观测：候选、分数、门限模式、分段耗时落库 | `ai_retrieval_log` 含 `gate_mode` 与三段耗时 |
| G8 | **离线降级**：LLM 或 Embedding 不可用时回退本地引擎 | 断 key 后 `/api/ai/chat` 仍返回答案，`degraded=true` |
| G9 | 零新增 Maven 依赖 | `pom.xml` 的 `<dependencies>` 不变（Hutool 5.8.27 已在） |
| G10 | **消除"两套检索实现"**：`retrieveContext()` 委托给 `HybridRetriever` | `DeepSeekChatService:142` 无需改动即获得新检索能力 |

### 2.2 非目标

见 §9 YAGNI 清单。

---

## 3. 架构与数据流

```
用户提问（/api/ai/chat，需登录）
   │
   ├─ 查询预处理：tokenize（复用现有分词）+ 同义词扩展
   │
   ├─【路径1 向量召回】EmbeddingClient.embed(question)
   │        └→ VectorIndex.search(cosine) ───→ Top20 (chunk, vectorCosine)
   │
   └─【路径2 关键词召回】KeywordRetriever.cosineScore()
            └──────────────────────────────→ Top20 (chunk, keywordScore)
   │
   ▼
HybridRetriever：RRF 融合（仅排序）+ 同文档去重 → 门限判定（§5.5）
   │
   ├─ hasQualifiedMaterial = false ──→ 直接拒答（不调用 LLM）
   │
   └─ true ──→ RagService 组装 prompt（材料带元信息）
                     │
                     ▼
              LlmClient.complete()（Qwen，OpenAI 兼容）
                     │
                     ├─ 失败/超时/未配置 ──→ 降级：关键词 top1 作答案，degraded=true
                     ▼
              引用解析与校验（剔除幻觉引用号）
                     │
                     ▼
   ChatResponse{answer, citations[], confidence, unknown, degraded, costMs}
                     │
                     ├─→ ai_chat_history（现有表，history 接口保持兼容）
                     └─→ ai_retrieval_log（新表）
```

---

## 4. 数据模型

新增 3 张表，**不修改任何现有表结构**。脚本：`docs/sql/2026-09-18_rag_schema.sql`

```sql
-- 知识文档：一个业务来源 = 一条文档
CREATE TABLE `kb_document` (
  `id`           bigint       NOT NULL AUTO_INCREMENT,
  `source_type`  varchar(50)  NOT NULL COMMENT '来源类型，见 §5.7 映射表',
  `source_id`    bigint       DEFAULT NULL COMMENT '来源业务表主键',
  `title`        varchar(300) NOT NULL COMMENT '文档标题，用于引用展示',
  `category`     varchar(50)  DEFAULT NULL COMMENT '分类，复用现有分类口径',
  `url_path`     varchar(200) DEFAULT NULL COMMENT '引用跳转路径，可为空(ai_knowledge)',
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
  `id`              bigint       NOT NULL AUTO_INCREMENT,
  `document_id`     bigint       NOT NULL,
  `chunk_index`     int          NOT NULL COMMENT '文档内序号，从0开始',
  `content`         text         NOT NULL COMMENT '分块正文（参与向量化）',
  `search_terms`    varchar(1000) DEFAULT NULL COMMENT '关键词/同义词等检索辅助词（**不参与向量化**，仅供关键词路径使用）',
  `char_start`      int          DEFAULT NULL COMMENT '在原文中的起始偏移，用于溯源',
  `char_end`        int          DEFAULT NULL COMMENT '在原文中的结束偏移',
  `content_hash`    char(64)     NOT NULL COMMENT '分块正文 SHA-256，用于增量向量化',
  `embedding`       mediumtext   DEFAULT NULL COMMENT 'JSON float 数组文本，格式见 §4.2',
  `embedding_model` varchar(50)  DEFAULT NULL COMMENT '如 text-embedding-v3',
  `dim`             int          DEFAULT NULL COMMENT '向量维度，如 1024',
  `status`          tinyint      DEFAULT 0 COMMENT '0-待向量化 1-已向量化 2-向量化失败',
  `create_time`     datetime     DEFAULT CURRENT_TIMESTAMP,
  `update_time`     datetime     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_doc_chunk` (`document_id`,`chunk_index`),
  KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='RAG 知识分块表';

-- 检索日志：可观测性
CREATE TABLE `ai_retrieval_log` (
  `id`              bigint       NOT NULL AUTO_INCREMENT,
  `session_id`      varchar(64)  DEFAULT NULL,
  `question`        varchar(500) NOT NULL,
  `vector_hits`     int          DEFAULT 0 COMMENT '向量召回命中数',
  `keyword_hits`    int          DEFAULT 0 COMMENT '关键词召回命中数',
  `gate_mode`       varchar(10)  DEFAULT NULL COMMENT '本次请求使用的门限模式: vector / keyword',
  `top_score`       decimal(6,4) DEFAULT NULL COMMENT '当前 gate_mode 下 top1 的**原始分**（vectorCosine 或 keywordScore），不是 RRF 分',
  `final_chunk_ids` varchar(500) DEFAULT NULL COMMENT '最终进入 prompt 的 chunk id，逗号分隔',
  `is_unknown`      tinyint      DEFAULT 0 COMMENT '是否拒答',
  `degraded`        tinyint      DEFAULT 0 COMMENT '是否降级回答',
  `embedding_ms`    int          DEFAULT NULL,
  `retrieval_ms`    int          DEFAULT NULL,
  `generate_ms`     int          DEFAULT NULL COMMENT '为 NULL 表示未调用 LLM（拒答路径）',
  `create_time`     datetime     DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_create_time` (`create_time`),
  KEY `idx_is_unknown` (`is_unknown`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='RAG 检索日志表';
```

### 4.1 关键设计决定：向量存 `MEDIUMTEXT`（JSON）而非 `BLOB float32`

| 方案 | 空间（1000 chunk × 1024 维） | 优点 | 缺点 |
|---|---|---|---|
| `MEDIUMTEXT` JSON（**选定**） | ≈ 10 MB | 可读、可直接 `SELECT` 出来演示、无二进制兼容问题 | 空间大、解析有开销 |
| `BLOB` float32 | ≈ 4 MB | 省 60% 空间 | 不可读、Demo 时无法展示 |

选 JSON 的理由：本项目规模下空间无意义（10 MB），但**面试现场能直接把向量 `SELECT` 出来给人看**，演示价值高于空间优化。规模上到十万级 chunk 时应迁 Milvus/pgvector，此时存储格式本身会被替换。

### 4.2 Embedding 序列化格式（精确定义）

- **文本格式**：标准 JSON 数组，小数点后最多 6 位，无多余空格。示例：`[-0.022868,0.042135,-0.083961,...]`
- **写入**：`JSONUtil.toJsonStr(float[])`（Hutool）
- **读取**：`JSONUtil.parseArray(text)` → `JSONArray` → 逐位 `getFloat(i)` 填 `float[]`
- **明确不复用** `AiQaServiceImpl.extractJsonField()` —— 它是单字段字符串扫描器，无法解析数组（本设计修订原因之一）
- **脏数据隔离**：反序列化失败或长度 ≠ `dim` → 该 chunk 标 `status=2`、`log.warn`、跳过，**不中断索引构建**

### 4.3 关键设计决定：`ai_knowledge` / `guide_faq` 的 Q/A 对**不切分**

一行 = 一条 `kb_document` + **恰好 1 条** `kb_chunk`：

- chunk 正文 = `question + "\n" + answer`（问题提供语义入口，答案提供细节匹配）
- `keywords` / `synonyms` **不拼进 content**（避免污染向量语义），改存 `kb_chunk.search_terms`，仅供关键词路径使用
- 需要切分的是长文本来源：`guide_registration_step`、`guide_major`、`life_*`、`campus_building`、`sys_news`

---

## 5. 组件设计

### 5.1 `ChunkSplitter`（`com.freshman.rag.ChunkSplitter`）

```java
List<Chunk> split(String text, ChunkOptions opts);
// Chunk: { index, content, charStart, charEnd }
```

切分规则（按优先级）：

1. **结构感知**：按标题/编号边界分段 —— 正则匹配 `^#{1,6}\s`、`^[一二三四五六七八九十]+、`、`^（[一二三四五六七八九十\d]+）`、`^\d+[.、]`、`^步骤\d+`，以及连续空行（≥2）
2. **段内定长**：单段超过 `size` 时，按中文句末标点（`。！？；`）贪心拼接到 ≤ `size` 字
3. **overlap**：相邻 chunk 重叠 `overlap` 字，**回退到最近句边界**，禁止从句中间截断
4. **碎片合并**：长度 < `min-size` 的 chunk 合并到前一个（首块则并入后一个）
5. **偏移记录**：`charStart` / `charEnd` 相对原文
6. **硬切兜底**：单句仍超长时按 `size` 硬切，避免死循环

边界情况（必须有单测）：空文本、纯空白、超长无标点、仅标题无正文、`size <= min-size` 的非法配置（启动时校验并拒绝启动）。

### 5.2 `EmbeddingClient`（`com.freshman.rag.EmbeddingClient`）

```java
float[] embed(String text);
List<float[]> embedBatch(List<String> texts);
```

- 协议：OpenAI 兼容 `POST {api-url}`，`{"model":"text-embedding-v3","input":[...],"dimensions":1024}`（**已实测 HTTP 200 / 1024 维**）
- 实现：JDK `HttpURLConnection` 或 `java.net.http.HttpClient` + Hutool `JSONUtil`（零新依赖）
- 批量：每请求 ≤ 25 条（`batch-size`），必须**按响应中的 `index` 字段对齐**输入顺序（不假设返回顺序）
- 归一化：返回后立即 L2 归一化，使点积等价于余弦
- 重试：指数退避 2 次（1s / 2s）
- **失败隔离**：仍失败的 chunk 标 `status=2`，不阻塞同批其它 chunk，不中断索引重建
- 超时：连接 10s / 读取 30s

### 5.3 `VectorIndex`（`com.freshman.rag.VectorIndex`）

```java
void rebuild();
List<ScoredChunk> search(float[] query, int topK);   // ScoredChunk.vectorCosine 有值
```

- 结构：`float[][] vectors` + `ChunkMeta[] metas` 平行数组
- 加载：`ApplicationRunner` 启动时加载；**失败只 `log.warn`**，索引为空时 `gate_mode=keyword`（自动退化，不中断启动）
- 检索：归一化后点积 = 余弦；用**固定大小最小堆**取 TopK，避免全排序
- 热重建：新数组构建完成后**原子替换 `volatile` 引用**，读路径无锁
- 规模说明（写进类注释）：1000 × 1024 ≈ 100 万次乘加，<5 ms；十万级以上需 HNSW/IVF

### 5.4 `KeywordRetriever`（`com.freshman.rag.KeywordRetriever`）

从 `AiQaServiceImpl` 抽出，提供**两个语义不同的打分方法**（这是修订重点）：

| 方法 | 公式 | 用途 |
|---|---|---|
| `cosineScore(query, chunk)` | **纯 TF-IDF 余弦**（查询 TF-IDF 向量 vs chunk TF-IDF 向量） | 混合检索的关键词路径（进入 RRF） |
| `fusionScore(query, chunk)` | 原四策略：Jaccard 0.3 + 余弦 0.5 + **分类加成 0.2** + priority 加成 | **仅供降级路径**复用，行为与改造前逐例一致 |

- **混合检索的关键词路径使用 `cosineScore`，不使用 `fusionScore`**。原因：`fusionScore` 含 0.06 分类底分与 `priority*0.01` 底分，正是缺陷 1（"任何问题都能凑满 Top3"）的成因，绝不能带进 RRF 候选集
- chunk 的 token 来源：`content` + `search_terms` + 所属 `kb_document.title`
- IDF 语料基于 chunk 集合统计
- **回归保证**：`fusionScore` 路径（降级场景）与改造前逐例一致，由 `KeywordRetrieverTest` 用改造前记录的一组「问题 → top1 答案」做回归断言

### 5.5 `HybridRetriever`（`com.freshman.rag.HybridRetriever`）

```java
RetrievalResult retrieve(String question, int topK);
// RetrievalResult: { List<ScoredChunk> chunks, boolean hasQualifiedMaterial,
//                    String gateMode /*vector|keyword*/, double topScore }
```

#### 5.5.1 三种分数的语义（修订重点 —— 原设计在此自相矛盾）

每个候选 chunk 可携带三个分数，**语义严格区分、不可混用**：

| 分数 | 含义 | 取值范围 | 是否参与门限 |
|---|---|---|---|
| `vectorCosine` | 向量路径的**原始余弦**（归一化向量点积） | 0 ~ 1 | ✅（`gate_mode=vector` 时） |
| `keywordScore` | 关键词路径的**原始 TF-IDF 余弦** | 0 ~ 1 | ✅（`gate_mode=keyword` 时） |
| `rrfScore` | 融合分 `Σ 1/(k + rank)`，`k=60` | ≈ 0.016 ~ 0.033 | ❌ **仅用于排序** |

> ⚠️ **RRF 分丢弃了绝对相似度**（两条路径各进 Top20 时，RRF 值域只有 0.016~0.033）。把 RRF 分与 0.35 门限比较会导致**永远拒答**、`relative-floor` 静默失效 —— 这是必须在实现前钉死的语义。

**`ScoredChunk` 字段定义（跨规格契约，子系统 B 的 `search_knowledge` 工具依赖此结构）**：

```java
package com.freshman.rag.dto;

public class ScoredChunk {
    private Long   chunkId;        // kb_chunk.id
    private Long   documentId;     // kb_document.id
    private String sourceType;     // 见 §5.7 映射表
    private Long   sourceId;       // 来源业务表主键
    private int    chunkIndex;
    private String title;          // kb_document.title，用于引用展示
    private String urlPath;        // 可为 null（ai_knowledge）
    private String content;        // chunk 正文
    private String snippet;        // 截断至 200 字的摘要，供引用卡片与工具结果使用
    private Double vectorCosine;   // 可空
    private Double keywordScore;   // 可空
    private double rrfScore;       // 仅排序
}
```
- 子系统 B 只允许读 `title / urlPath / snippet / sourceType / sourceId` 用于构造 `search_knowledge` 的返回材料；**不得读 `rrfScore` 作为相似度展示**
- `HybridRetriever` 全限定名：`com.freshman.rag.HybridRetriever`；入口方法 `RetrievalResult retrieve(String question, int topK)`
- `RetrievalResult` 全限定名：`com.freshman.rag.dto.RetrievalResult`，字段 `{List<ScoredChunk> chunks, boolean hasQualifiedMaterial, String gateMode, double topScore}`

#### 5.5.2 门限规则（每次请求只有一种 `gate_mode`，确定性、可解释）

| 条件 | `gate_mode` | 绝对门限 | 相对门限 |
|---|---|---|---|
| 向量索引可用 **且** 问题 embedding 成功 | `vector` | 向量路径 top1 的 `vectorCosine` ≥ `min-score`(0.35) | 候选自身 `vectorCosine` ≥ `relative-floor`(0.6) × 向量路径 top1 的 `vectorCosine` |
| 索引为空 / embedding 失败 / 主动降级 | `keyword` | 关键词路径 top1 的 `keywordScore` ≥ `keyword-min-score`(0.25) | 候选自身 `keywordScore` ≥ `relative-floor`(0.6) × 关键词路径 top1 的 `keywordScore` |

规则细节：

1. **门限只作用于当前 `gate_mode` 对应的那一种原始分**；另一条路径的候选只参与 RRF 排序，不参与门限判定
2. `gate_mode=vector` 时**不回退**到关键词门限（避免两套口径混用导致结果不可解释）；若向量门限全部不过 → 直接拒答
3. 不满足相对门限的候选被剔除出**材料集**，但仍在日志中记录（便于分析"为什么这条没进材料"）
4. `gate_mode` 与 `top_score` 一并写入 `ai_retrieval_log`，保证"拒答依据"可追溯

#### 5.5.3 融合与去重

- **RRF**：`rrfScore(d) = Σ_paths 1 / (rrf-k + rank_path(d))`，`rrf-k=60`
- 选 RRF 而非加权求和：无需为两条路径的不同量纲标定权重、对分数分布鲁棒、是工业界默认做法
- **同文档去重**：同一 `document_id` 最多保留 `max-per-document`(2) 条，按 `rrfScore` 取高（避免 Top5 被同段相邻 chunk 占满）

#### 5.5.4 与现有抽象的衔接（修复"两套检索实现"）

`AiQaService.retrieveContext(question, topK)` **保留签名**，实现改为 `HybridRetriever` 的薄封装。其唯一调用方 `DeepSeekChatService.java:142`（`useRag=true` 分支）无需任何改动即获得新检索能力 —— 这消除面试文档中点名的"两条 RAG 路径行为不一致"批评。

### 5.6 `RagService` 与 `LlmClient`（修订：补齐缺失的 LLM 客户端抽象）

```java
public interface LlmClient {
    LlmResult complete(String systemPrompt, String userMessage);
    // LlmResult: { boolean success, String content, int totalTokens, long costMs, String error }
}
```

- 实现 `OpenAiCompatibleLlmClient`：**HTTP 实现从 `AiQaServiceImpl.callLlmApi()` 抽出**，复用现有 `app.ai.llm.*` 配置（`provider=qwen`、`qwen-plus`）
- **可注入是硬要求**：G4 需断言"拒答时 LLM 调用次数 = 0"，测试注入 `RecordingLlmClient`（计数）与 `ScriptedLlmClient`（返回固定答案）
- 与子系统 B 的 `DeepSeekClient` 的关系：**有意独立**。两者协议同为 OpenAI 兼容，但（a）配置命名空间不同，（b）请求体不同（Agent 需携带 `tools`），（c）失败语义不同（本设计降级到本地引擎，Agent 降级到无工具 chat）。统一为公共客户端属后续独立技术债，不在本次范围

```java
ChatResponse ask(ChatRequest req);   // RagService 对外唯一入口
```

**Prompt 模板**（材料块带元信息）：

```
你是东北石油大学智慧迎新系统的迎新助手（哈基油油子），专门为大一新生解答入学相关问题。

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

> 注：材料块中的"相似度"取该 chunk 在**当前 `gate_mode` 下的原始分**（`vectorCosine` 或 `keywordScore`），**绝不展示 `rrfScore`**。人设统一为「迎新助手（哈基油油子）」，与现有 `/ai-chat` 页面一致（`AiQaServiceImpl.java:708`）。

**引用处理**：

- 解析答案中所有 `[n]` → 映射到 `citations[]`（`documentId` / `title` / `urlPath` / `chunkId` / `snippet` / `score`）
- **幻觉引用检测**：答案引用了不存在的编号（如只有 3 条材料却出现 `[7]`）→ 从 `citations` 剔除 + `log.warn` + 该次回答标 `degraded=true`
- 未被引用的材料照常返回（前端折叠展示，允许用户自查）

**拒答路径**：`hasQualifiedMaterial=false` → `unknown=true` 固定话术 + `getQuickQuestions()` 推荐，**不调用 LLM**，`ai_chat_history.is_unknown=1`，`ai_retrieval_log.generate_ms` 记 `NULL`

**降级路径**：`LlmClient` 返回失败/未配置 → 用关键词路径 top1 的答案内容，`degraded=true`，答案前缀"（离线降级回答）"

### 5.7 `KnowledgeIndexer` 与来源映射（修订：补齐 source → url_path → 切分策略）

```java
IndexReport rebuildAll(boolean force);
```

流程：`抽取（DocumentSource 实现）` → `清洗（去 HTML 标签/折叠空白）` → `切分` → `content_hash 比对（未变跳过）` → `Embedding` → `落 kb_chunk` → `VectorIndex.rebuild()`

**来源映射表（实现依据，路由已按现有 Controller 核实）**：

| # | `source_type` | 来源表 | `title` 取值 | `url_path` | 切分策略 | 纳入 |
|---|---|---|---|---|---|---|
| 1 | `ai_knowledge` | `ai_knowledge` | `question` | 无（前端展开原文，标"AI 知识库"） | 不切分（§4.3） | ✅ |
| 2 | `guide_faq` | `guide_faq` | `question` | `/guide/faq` | 不切分（§4.3） | ✅ |
| 3 | `guide_registration_step` | `guide_registration_step` | `title` | `/guide/registration` | 按 `step_no`/`title` 边界切分 | ✅ |
| 4 | `guide_major` | `guide_major` | `name` | `/guide/majors` | 按段落切分（`description`+`courses`+`career_prospect`） | ✅ |
| 5 | `life_dormitory` | `life_dormitory` | 宿舍区名 | `/life/dormitory` | 按段落切分 | ✅ |
| 6 | `life_cafeteria` | `life_cafeteria` | 食堂名 | `/life/cafeteria` | 按段落切分 | ✅ |
| 7 | `life_club` | `life_club` | `name` | `/life/clubs`（**复数**） | 按段落切分 | ✅ |
| 8 | `life_activity` | `life_activity` | `title` | `/life/activities` | 按段落切分 | ✅ |
| 9 | `campus_building` | `campus_building` | 建筑名 | `/campus/{id}`（**无列表页，只有详情页**） | 按段落切分 | ✅ |
| 10 | `sys_news` | `sys_news` | `title` | `/news/{id}` | 按段落切分（`summary`+`content`） | ✅ |
| — | `guide_teacher` | `guide_teacher` | — | — | — | ❌ **排除**：该表含 `email` 等个人信息，不应进入第三方 LLM 请求 |

- `url_path` 为空的来源（`ai_knowledge`），前端引用卡片降级为「标题 + 展开原文」，不生成跳转链接
- 返回 `IndexReport{documentCount, chunkCount, embeddedCount, skippedCount, failedCount, costMs}`

### 5.8 `RagEvalRunner`（修订：评估集使用稳定身份）

评估集：`docs/rag/eval-set.jsonl`，**期望值使用稳定 ref 键，不使用 `kb_chunk.id`**（`id` 是自增的，重建后会漂移，导致基线静默失效）：

```json
{"id":1,"question":"宿舍有空调吗","expected_refs":["ai_knowledge:27:0"],"answerable":true}
{"id":2,"question":"报到流程是怎样的","expected_refs":["ai_knowledge:2:0","guide_registration_step:1:0"],"answerable":true}
{"id":3,"question":"学校有没有游泳池","expected_refs":[],"answerable":false}
```

- ref 格式：`{source_type}:{source_id}:{chunk_index}`
- 评估器启动时把 ref 解析为**当前** `kb_chunk.id` 再比对；解析不到该 ref 时**报错而非静默跳过**（防止语料变动后指标虚高）
- 24 条，其中**至少 5 条 `answerable=false`**（验证门限真的在起作用）
- 指标：`Hit@1`、`Hit@3`、`MRR`、拒答正确率、引用准确率（答案中 `[n]` 全部指向真实材料的比例）
- 载体：`RagEvalRunnerTest`（`@Tag("eval")`，默认跳过）+ `GET /admin/ai/eval` 输出 Markdown 表
- **必须产出改造前 vs 改造后对比表**，写入 `docs/rag/eval-report.md`

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
      min-score: 0.35           # vector 模式绝对门限（原始余弦）
      keyword-min-score: 0.25   # keyword 模式绝对门限（原始 TF-IDF 余弦）
      relative-floor: 0.6       # 相对门限系数（× top1 原始分）
      rrf-k: 60                 # RRF 平滑常数（仅影响排序）
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

启动时校验：`size > min-size > 0`、`overlap < size`、`relative-floor ∈ (0,1]`、`top-k-final ≤ min(top-k-vector, top-k-keyword)`、`min-score ∈ (0,1]`、`dimensions > 0`。非法配置**启动即失败并给出明确报错**。

> **注册方式**：项目**没有** `@ConfigurationPropertiesScan`（`FreshmanApplication` 仅声明 `@MapperScan("com.freshman.mapper")`），因此 `RagProperties` 必须写成 `@Component + @ConfigurationProperties(prefix = "app.ai.rag")` 才会生效（或显式加 `@EnableConfigurationProperties`）。

---

## 7. 接口与前端

### 7.1 接口（修订：权限按 `SecurityConfig` 实际配置更正）

| 方法 | 路径 | 权限（实际） | 依据 / 说明 |
|---|---|---|---|
| POST | `/api/ai/chat` | **`authenticated`**（非公开） | `SecurityConfig.java:70` → `.requestMatchers("/ai-chat", "/api/ai/**").authenticated()` |
| GET | `/api/ai/history` 等现有接口 | `authenticated` | 同上 |
| POST | `/admin/ai/reindex` | `hasRole("ADMIN")` | `SecurityConfig.java:84` → `/admin/**` 已被 gate |
| GET | `/admin/ai/eval` | `hasRole("ADMIN")` | 同上 |
| GET | `/admin/ai/retrieval-stats` | `hasRole("ADMIN")` | 同上 |

- 三个新管理端点挂在 `AdminController`（`@RequestMapping("/admin")`），遵循现有后台页面模式，**无需改 `SecurityConfig`**
- `AiQaService.chat()` **签名不变**；`ChatResponse` 新增 `citations`(List)、`degraded`(Boolean)、`costMs`(Long)，旧前端忽略新字段不受影响

### 7.2 前端 `ai-chat.html`

- 答案下方「参考来源」折叠块：`序号｜标题｜相似度`，有 `url_path` 的可点击跳转，无链接的展开原文
- 置信度可视化（现有 `confidence` 字段终于有用）
- 拒答样式与普通回答区分（不同底色 + "知识库暂未收录"标签）
- 降级回答显示"离线降级"标签

---

## 8. 测试与评估计划

> **测试基建从零建立**：`src/test` 目录当前不存在。M1 的第一个任务就是创建 `src/test/java` 与 `src/test/resources`，并确认 `spring-boot-starter-test`（已在 `pom.xml`）可用。
>
> **必须补 surefire 配置**：`pom.xml` 当前**没有任何 surefire 配置**，因此仅加 `@Tag("eval")` **不会排除任何测试** —— 付费的真实 API 评估会在 `mvn test` 时被执行。需在 `pom.xml` 加插件配置 `<configuration><excludedGroups>eval</excludedGroups></configuration>`（**仅插件配置，不新增依赖，G9 仍然成立**）。所有非 eval 测试必须是**不依赖 Spring 上下文与 MySQL 的纯单元测试**，否则 DoD 的「`mvn test` 全绿」不可达。

| 测试类 | 覆盖 |
|---|---|
| `ChunkSplitterTest` | 空文本/纯空白/超长无标点/仅标题/碎片合并/overlap 句边界/非法配置 |
| `EmbeddingClientTest` | 本地 HTTP stub：正常批量、**返回乱序按 `index` 对齐**、超时重试、单条失败隔离 |
| `VectorIndexTest` | 归一化后点积=余弦、TopK 最小堆正确性、热重建原子性、空索引退化 |
| `KeywordRetrieverTest` | `cosineScore` 不含量纲污染；`fusionScore` 与改造前**逐例一致**（回归保护） |
| `HybridRetrieverTest` | RRF 仅排序、**门限只作用于对应 gate_mode 的原始分**、`gate_mode` 切换、同文档去重、全不过时拒答 |
| `RagServiceTest` | 拒答时 `RecordingLlmClient.callCount == 0`、引用解析、**幻觉引用剔除**、LLM 失败降级 |
| `KnowledgeIndexerTest` | 增量跳过（hash 未变不重复向量化）、失败 chunk 标 `status=2`、`url_path` 映射正确 |
| `RagEvalRunnerTest` | `@Tag("eval")`，真实 API，产出指标；ref 解析失败时报错 |

集成验证（我本地执行，**必须用 Java 发 HTTPS，curl 仅用于 localhost 明文**）：

1. `mvn test`（缓存 Maven 3.9.12 `-o` + JBR 25，`-Dmaven.compiler.release=17`）
2. 连本地 MySQL 3306 执行 schema 脚本 → 跑 `POST /admin/ai/reindex` → 校验 `kb_chunk` 有向量
3. 启动应用 → `curl http://localhost:8080/api/ai/chat` 验证：正常题带引用、无关题被拒答、断 key 走降级

---

## 9. 明确不做（YAGNI）

| 不做 | 理由 |
|---|---|
| 引入向量库（Milvus / pgvector / Redis） | 千级 chunk 下暴力检索 <5 ms，引入运维成本与依赖，收益为负 |
| Rerank 模型（gte-rerank 等） | 属"进阶档"；RRF + 双门限已能解决噪声污染问题 |
| 查询改写 / HyDE / 多查询 | 同上，且成倍增加 API 成本 |
| 流式输出（SSE） | 与现有 Thymeleaf + `fetch` 架构改动面大，答辩演示收益低 |
| 知识库 CRUD 管理页面 | 只提供 reindex 端点；内容维护仍走 SQL/现有后台 |
| PDF/Word 解析、多模态 | 无此数据源需求 |
| 统一公共 LLM 客户端（与 Agent 共用） | 独立技术债，见 §5.6；本次保持两个有意的独立抽象 |
| 会话管理统一重构（浮窗/AI页/DeepSeek页三套 sessionId） | 独立技术债，不在本次范围 |
| BM25 替换 TF-IDF | 会改变关键词召回行为，破坏回归基线；本次保留原算法 |
| `guide_teacher` 纳入知识库 | 含个人信息，不进第三方 LLM 请求 |

---

## 10. 风险与缓解

| 风险 | 影响 | 缓解 |
|---|---|---|
| ~~Embedding API 不可用~~ | — | ✅ **已实测通过**（HTTP 200 / 1024 维 / 现有 key 具备权限），风险退役 |
| Embedding 调用成本/限流 | 重建慢、超额 | `content_hash` 增量跳过 + 批量 25 条/请求 + 失败隔离不重试全量 |
| `application.yml` 中明文 API Key 已入库 | 安全 | 新配置改用 `${DASHSCOPE_API_KEY:}`；**建议轮换该 key** |
| `JAVA_HOME` 当前为 JDK 8 | 无法构建 | 构建时显式指定 JDK；已确认 JBR 25 可用 |
| JDK 25 运行 Spring Boot 3.2.5 可能不兼容 | 启动失败 | 编译用 `--release 17`；若运行异常则仅以 `mvn test` + 真实 MySQL 验证 |
| **本环境 curl/.NET 无法建 TLS** | 验证手段受限 | 外部 API 验证一律走 Java；`curl` 只用于 localhost 明文 |
| `src/test` 不存在 | 测试计划无法直接落地 | M1 首个任务建立测试基建 |
| 暴力检索规模上限 | 数据增长后变慢 | 类注释写明适用规模与迁移路径；`ai_retrieval_log.retrieval_ms` 提供实测依据 |
| 索引加载失败导致检索全空 | 功能静默退化 | 失败 `log.warn` + 自动转 `gate_mode=keyword` + 管理端点可查索引状态 |

---

## 11. 里程碑

| 里程碑 | 内容 | 完成判据 |
|---|---|---|
| **M0 前置验证** ✅ 已完成 | embedding 可用性、DeepSeek tools 支持、构建链路 | 见 §0，全部通过 |
| **M1 数据层** | 测试基建、DDL 脚本、`ChunkSplitter`、`EmbeddingClient`、`VectorIndex`、`KnowledgeIndexer`、reindex 端点 | chunk 与向量落库；`ChunkSplitterTest`/`EmbeddingClientTest`/`VectorIndexTest`/`KnowledgeIndexerTest` 全绿 |
| **M2 检索与生成** | `KeywordRetriever` 抽取（双打分）、`HybridRetriever`、`LlmClient`、`RagService`、拒答与降级、`ChatResponse` 扩展、`retrieveContext` 委托 | `HybridRetrieverTest`/`RagServiceTest`/`KeywordRetrieverTest` 全绿；`curl` 验证三类响应 |
| **M3 评估与可观测** | 评估集（稳定 ref）、`RagEvalRunner`、`ai_retrieval_log`、管理端点 | `eval-report.md` 产出改造前后对比表 |
| **M4 前端与收尾** | `ai-chat.html` 来源折叠块/置信度/拒答样式；文档与话术更新 | 人工走查 4 个场景通过 |

---

## 12. 新增文件清单

```
com.freshman.rag
  RagProperties.java          @ConfigurationProperties(app.ai.rag) + 启动校验
  ChunkSplitter.java
  EmbeddingClient.java
  VectorIndex.java            + ChunkMeta / ScoredChunk
  KeywordRetriever.java       cosineScore + fusionScore
  HybridRetriever.java        RRF + 门限 + 去重
  LlmClient.java              接口
  OpenAiCompatibleLlmClient.java
  RagService.java
  KnowledgeIndexer.java       + DocumentSource 接口与其 10 个实现
  RagEvalRunner.java
  dto/RetrievalResult.java / ScoredChunk.java / Citation.java / IndexReport.java
src/test/java/com/freshman/rag/   §8 的 8 个测试类 + RecordingLlmClient/ScriptedLlmClient
docs/sql/2026-09-18_rag_schema.sql
docs/rag/eval-set.jsonl
docs/rag/eval-report.md
```

---

## 13. 完成定义（DoD）

- [ ] `docs/sql/2026-09-18_rag_schema.sql` 可在现有库上幂等执行
- [ ] `pom.xml` 依赖列表未变（G9）；仅新增 surefire 的 `excludedGroups` 插件配置
- [ ] §8 全部单测通过（含 `fusionScore` 改造前后回归一致），且 `mvn test` **不会**触发付费 eval
- [ ] §2.1 的 G1–G10 逐条有证据（日志、截图或测试输出）
- [ ] `docs/rag/eval-report.md` 含改造前后 Hit@1/Hit@3/MRR/拒答正确率对比
- [ ] 断 key 场景实测通过（降级不报错、不阻断启动）
- [ ] 拒答题实测**未调用 LLM**（`RecordingLlmClient.callCount==0` 且 `ai_retrieval_log.generate_ms IS NULL`）
- [ ] `gate_mode=vector` 与 `gate_mode=keyword` 两条门限路径均有实测证据
