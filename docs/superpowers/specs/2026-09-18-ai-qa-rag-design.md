# AI 智能问答模块 RAG 化设计

- 日期：2026-09-18
- 范围：子系统 A —— `AI 智能问答`（`/ai-chat`）改造为完整 RAG 项目
- 关联文档：
  - `2026-09-18-deepseek-agent-design.md`（子系统 B，本设计是其前置依赖）
  - **`2026-09-18-build-verification-notes.md`（构建与验证环境事实源，含实测命令与三个必须绕开的坑）**
- 实施顺序：**本设计先做**。子系统 B 的 `search_knowledge` 工具将直接注入本设计产出的 `HybridRetriever`
- 修订记录：v3 —— v2 通过规格评审；本版落实评审的非阻塞项（降级打分器钉死、门限空值规则、DDL 幂等、落库职责、`retrieve` 的 topK 语义、来源正文列、测试分层、文件清单）

---

## 0. 前置验证结论（已实测）

**构建链路**：`BUILD SUCCESS` —— `Compiling 59 source files with javac [debug release 17]`，4.6 秒。
命令、必须绕开的三个坑（Maven 仓库在工作区外被沙箱拒写 / `_remote.repositories` 的镜像 id 不匹配 / 陈旧 `target/maven-status`）、以及 **必须用 JDK 21 而非 JDK 25**（Spring Boot 3.2.5 的 Lombok 不支持 JDK 25）详见 `2026-09-18-build-verification-notes.md`。

| 验证项 | 结果 |
|---|---|
| DashScope `text-embedding-v3` | ✅ **HTTP 200**，`data[0].embedding` 长度 **1024**；现有阿里 key **具备 Embedding 权限** |
| DeepSeek 的 `tools` 支持（子系统 B） | ✅ **HTTP 200**，`finish_reason="tool_calls"` |
| TLS | ⚠️ `curl`/`.NET` 的 schannel 在本会话不可用；**Java 正常**。外部 API 一律走 Java，`curl` 仅用于 localhost 明文 |

---

## 1. 背景与现状诊断

### 1.1 代码事实

| 事实 | 位置 |
|---|---|
| 本地语义匹配引擎：N-gram 分词 + 词典正向最大匹配 | `AiQaServiceImpl.java:293` |
| 同义词扩展字典（反向索引） | `AiQaServiceImpl.java:98`、`:127` |
| 三策略融合评分：Jaccard 0.3 + 余弦 0.5 + 分类 0.2 | `AiQaServiceImpl.java:53-59`、`:453` |
| 未知问题阈值 0.25（硬编码常量） | `AiQaServiceImpl.java:50` |
| LLM 路径：`callLlmApi()` **无条件**做 RAG，无开关 | `AiQaServiceImpl.java:684-710` |
| RAG 检索门限 `score > 0.1`、TopK 硬编码 3 | `AiQaServiceImpl.java:693`、`:701` |
| 检索入口已抽象：`retrieveContext(question, topK)` | `AiQaService.java:102-110` |
| 该方法的**唯一调用方**是 DeepSeek 的 RAG 模式 | `DeepSeekChatService.java:142` |
| 现有 JSON 解析是单字段字符串扫描器，**不能解析数组** | `AiQaServiceImpl.extractJsonField()` |
| 知识库为「一问一答」结构，100 条 | `freshman_orientation.sql:65-78`（`AUTO_INCREMENT=101`） |
| 长文本散落在 `guide_*` / `life_*` / `campus_building` / `sys_news` | `freshman_orientation.sql:188-608` |
| **`src/test` 目录不存在**（无任何单元测试） | 测试基建从零建立 |
| `pom.xml` **没有 surefire 配置** | 仅 `spring-boot-maven-plugin` |
| `AdminController` 是 `@Controller`（返回视图名），`@RequestMapping("/admin")` | `AdminController.java:26` |

### 1.2 已知缺陷（本设计要修掉的靶子）

以下 5 条来自项目自身的面试答辩文档，属于**已承认**的问题，因此是本设计的验收目标而非新发现：

1. **检索门限形同虚设**：分类底分 0.06 + `priority*0.01` + 字符 N-gram 假重叠，导致"几乎任何问题都能凑满 Top3"，宽松的垃圾材料比没有材料更危险（`面试防守稿-自圆其说.md:399`、`:509`）
2. **无引用溯源**：用户无法验证答案来自哪条知识
3. **无评估集**：效果不可量化
4. **核心算法参数硬编码**：阈值/权重/门限改一个都要重新编译（`面试防守稿-自圆其说.md:868`）
5. **可观测缺失**：未知问题统计有表无页面

### 1.3 "完整 RAG" 的定义（本设计的判据）

> RAG = 语料切分与索引 → 向量召回 → 混合融合 → 生成 → **引用溯源** → **拒答兜底** → **量化评估**。
> 缺任一环，只能叫"LLM + 检索"，不能叫 RAG。

---

## 2. 目标与非目标

### 2.1 目标（全部可测）

| 编号 | 目标 | 判定方式 |
|---|---|---|
| G1 | 知识来源扩展到 §5.7 映射表的 10 张表，完成切分并落库 | `kb_chunk` 中各 `source_type` 均有数据，长度分布符合配置 |
| G2 | 问题经 Embedding 后可在 **chunk 级**做向量检索 | `VectorIndex.search()` 返回带原始余弦的 chunk 列表 |
| G3 | 向量 + 关键词**双路召回**，RRF 融合 | `HybridRetrieverTest` 验证融合排序与同文档去重 |
| G4 | **双门限拒答**：无合格材料时**不调用 LLM** | `RagServiceTest` 断言 `RecordingLlmClient.callCount == 0` |
| G5 | 答案带 `[n]` 引用编号，可溯源到 `url_path` | 响应含 `citations[]`；前端可点击跳转 |
| G6 | 24 条评估集（稳定 ref 键），产出 `Hit@1`/`Hit@3`/`MRR`/拒答正确率/引用准确率 | `/admin/ai/eval` 输出 Markdown 对比表 |
| G7 | 全流程可观测：候选、分数、门限模式、分段耗时落库 | `ai_retrieval_log` 含 `gate_mode` 与三段耗时 |
| G8 | **离线降级**：LLM 或 Embedding 不可用时回退本地引擎 | 断 key 后 `/api/ai/chat` 仍返回答案，`degraded=true` |
| G9 | 零新增 Maven 依赖 | `pom.xml` 的 `<dependencies>` 不变（仅新增 surefire 插件配置） |
| G10 | **消除"两套检索实现"**：`retrieveContext()` 委托给 `HybridRetriever` | `DeepSeekChatService:142` 无需改动即获得新检索能力 |

---

## 3. 架构与数据流

```
用户提问（/api/ai/chat，authenticated）
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
HybridRetriever：RRF 融合（仅排序）+ 同文档去重 → 门限判定（§5.5.2）
   │
   ├─ hasQualifiedMaterial = false ──→ 直接拒答（不调用 LLM）
   │
   └─ true ──→ RagService 组装 prompt（材料带元信息）
                     │
                     ▼
              LlmClient.complete()（Qwen，OpenAI 兼容）
                     │
                     ├─ 失败/超时/未配置 ──→ 降级：KeywordRetriever.fusionScore() 的 top1 答案，
                     │                       degraded=true（**保持与改造前完全一致的行为**）
                     ▼
              引用解析与校验（剔除幻觉引用号）
                     │
                     ▼
   ChatResponse{answer, citations[], confidence, unknown, degraded, costMs}
                     │
                     ├─→ ai_chat_history（由 AiQaServiceImpl.chat() 落库，见 §5.6）
                     └─→ ai_retrieval_log（由 RagService 落库）
```

---

## 4. 数据模型

新增 3 张表，**不修改任何现有表结构**。脚本：`docs/sql/2026-09-18_rag_schema.sql`（目录需新建）
**幂等要求**：全部使用 `CREATE TABLE IF NOT EXISTS`（现有 `freshman_orientation.sql` 用的是 `DROP TABLE IF EXISTS` + `CREATE`，那是重建脚本；本脚本面向**已存在的开发库**，必须可重复执行）。

```sql
CREATE TABLE IF NOT EXISTS `kb_document` (
  `id`           bigint       NOT NULL AUTO_INCREMENT,
  `source_type`  varchar(50)  NOT NULL COMMENT '来源类型，见 §5.7 映射表',
  `source_id`    bigint       DEFAULT NULL COMMENT '来源业务表主键',
  `title`        varchar(300) NOT NULL COMMENT '文档标题，用于引用展示',
  `category`     varchar(50)  DEFAULT NULL,
  `url_path`     varchar(200) DEFAULT NULL COMMENT '引用跳转路径，可为空(ai_knowledge)',
  `content_hash` char(64)     NOT NULL COMMENT '正文 SHA-256，用于增量重建跳过未变更文档',
  `chunk_count`  int          DEFAULT 0,
  `status`       tinyint      DEFAULT 1 COMMENT '0-禁用 1-启用',
  `create_time`  datetime     DEFAULT CURRENT_TIMESTAMP,
  `update_time`  datetime     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_source` (`source_type`,`source_id`),
  KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='RAG 知识文档表';

CREATE TABLE IF NOT EXISTS `kb_chunk` (
  `id`              bigint        NOT NULL AUTO_INCREMENT,
  `document_id`     bigint        NOT NULL,
  `chunk_index`     int           NOT NULL COMMENT '文档内序号，从0开始',
  `content`         text          NOT NULL COMMENT '分块正文（参与向量化）',
  `search_terms`    varchar(1000) DEFAULT NULL COMMENT '关键词/同义词等检索辅助词（**不参与向量化**，仅供关键词路径）',
  `char_start`      int           DEFAULT NULL,
  `char_end`        int           DEFAULT NULL,
  `content_hash`    char(64)      NOT NULL COMMENT '分块正文 SHA-256',
  `embedding`       mediumtext    DEFAULT NULL COMMENT 'JSON float 数组文本，格式见 §4.2',
  `embedding_model` varchar(50)   DEFAULT NULL,
  `dim`             int           DEFAULT NULL,
  `status`          tinyint       DEFAULT 0 COMMENT '0-待向量化 1-已向量化 2-向量化失败',
  `create_time`     datetime      DEFAULT CURRENT_TIMESTAMP,
  `update_time`     datetime      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_doc_chunk` (`document_id`,`chunk_index`),
  KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='RAG 知识分块表';

CREATE TABLE IF NOT EXISTS `ai_retrieval_log` (
  `id`              bigint        NOT NULL AUTO_INCREMENT,
  `session_id`      varchar(64)   DEFAULT NULL,
  `question`        varchar(500)  NOT NULL,
  `vector_hits`     int           DEFAULT 0,
  `keyword_hits`    int           DEFAULT 0,
  `gate_mode`       varchar(10)   DEFAULT NULL COMMENT '本次请求的门限模式: vector / keyword',
  `top_score`       decimal(6,4)  DEFAULT NULL COMMENT '当前 gate_mode 下 top1 的**原始分**（vectorCosine 或 keywordScore），不是 RRF 分',
  `final_chunk_ids` varchar(500)  DEFAULT NULL,
  `is_unknown`      tinyint       DEFAULT 0,
  `degraded`        tinyint       DEFAULT 0,
  `embedding_ms`    int           DEFAULT NULL,
  `retrieval_ms`    int           DEFAULT NULL,
  `generate_ms`     int           DEFAULT NULL COMMENT '为 NULL 表示未调用 LLM（拒答路径）',
  `create_time`     datetime      DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_create_time` (`create_time`),
  KEY `idx_is_unknown` (`is_unknown`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='RAG 检索日志表';
```

### 4.1 关键设计决定：向量存 `MEDIUMTEXT`（JSON）而非 `BLOB float32`

| 方案 | 空间（1000 chunk × 1024 维） | 优点 | 缺点 |
|---|---|---|---|
| `MEDIUMTEXT` JSON（**选定**） | ≈ 10 MB | 可读、可直接 `SELECT` 出来演示 | 空间大、解析有开销 |
| `BLOB` float32 | ≈ 4 MB | 省 60% 空间 | 不可读、Demo 时无法展示 |

本项目规模下空间无意义（10 MB），但**面试现场能直接把向量 `SELECT` 出来给人看**，演示价值高于空间优化。

### 4.2 Embedding 序列化格式

- **写入**：`JSONUtil.toJsonStr(float[])`（Hutool），直接使用其默认输出，**不自行格式化小数位**（Java float 的实际十进制表示不保证固定位数）
- **读取**：`JSONUtil.parseArray(text)` → `JSONArray` → 逐位 `getFloat(i)` 填 `float[]`
- **明确不复用** `AiQaServiceImpl.extractJsonField()` —— 它是单字段字符串扫描器，无法解析数组
- **脏数据隔离**：反序列化失败或长度 ≠ `dim` → 该 chunk 标 `status=2`、`log.warn`、跳过，**不中断索引构建**

### 4.3 关键设计决定：Q/A 对**不切分**

`ai_knowledge` / `guide_faq` 一行 = 一条 `kb_document` + **恰好 1 条** `kb_chunk`：

- chunk 正文 = `question + "\n" + answer`（问题提供语义入口，答案提供细节匹配）
- `keywords` / `synonyms` **不拼进 content**（避免污染向量语义），改存 `kb_chunk.search_terms`，仅供关键词路径使用
- 需要切分的是长文本来源，见 §5.7

---

## 5. 组件设计

### 5.1 `ChunkSplitter`（`com.freshman.rag.ChunkSplitter`）

```java
List<Chunk> split(String text, ChunkOptions opts);
// com.freshman.rag.dto.Chunk: { int index, String content, int charStart, int charEnd }
```

切分规则（按优先级）：

1. **结构感知**：按标题/编号边界分段 —— 正则匹配 `^#{1,6}\s`、`^[一二三四五六七八九十]+、`、`^（[一二三四五六七八九十\d]+）`、`^\d+[.、]`、`^步骤\d+`，以及连续空行（≥2）
2. **段内定长**：单段超过 `size` 时，按中文句末标点（`。！？；`）贪心拼接到 ≤ `size` 字
3. **overlap**：相邻 chunk 重叠 `overlap` 字，**回退到最近句边界**，禁止从句中间截断
4. **碎片合并**：长度 < `min-size` 的合并到前一个（首块则并入后一个）
5. **偏移记录**：`charStart` / `charEnd` 相对原文
6. **硬切兜底**：单句仍超长时按 `size` 硬切，避免死循环

边界情况（必须有单测）：空文本、纯空白、超长无标点、仅标题无正文、`size <= min-size` 非法配置（启动时校验拒绝）。

### 5.2 `EmbeddingClient`（`com.freshman.rag.EmbeddingClient`）

```java
float[] embed(String text);
List<float[]> embedBatch(List<String> texts);
```

- 协议：OpenAI 兼容 `POST {api-url}`，`{"model":"text-embedding-v3","input":[...],"dimensions":1024}`（**已实测 HTTP 200 / 1024 维**）
- **HTTP 客户端统一用 `java.net.http.HttpClient`（JDK 11+）** —— 不选 `HttpURLConnection`：本会话实测 `HttpClient` 的 TLS 可用且 API 更清晰。JSON 用 Hutool（零新依赖）
- 批量：每请求 ≤ 25 条（`batch-size`），**按响应中的 `index` 字段对齐**输入顺序（不假设返回顺序）
- 归一化：返回后立即 L2 归一化，使点积等价于余弦
- 重试：指数退避 2 次（1s / 2s）
- **失败隔离**：仍失败的 chunk 标 `status=2`，不阻塞同批其它 chunk
- 超时：连接 10s / 读取 30s

### 5.3 `VectorIndex`（`com.freshman.rag.VectorIndex`）

```java
void rebuild();
List<ScoredChunk> search(float[] query, int topK);
```

- 结构：`float[][] vectors` + `ChunkMeta[] metas` 平行数组
- 加载：`ApplicationRunner` 启动时加载，**只加载 `kb_chunk.status = 1` 且 `embedding IS NOT NULL` 的行**；加载失败只 `log.warn`，索引为空时自动转 `gate_mode=keyword`（不中断启动）
- 检索：归一化后点积 = 余弦；用**固定大小最小堆**取 TopK
- 热重建：新数组构建完成后**原子替换 `volatile` 引用**，读路径无锁
- 规模说明（写进类注释）：1000 × 1024 ≈ 100 万次乘加，<5 ms；十万级以上需 HNSW/IVF

### 5.4 `KeywordRetriever`（`com.freshman.rag.KeywordRetriever`）

从 `AiQaServiceImpl` 抽出，提供**两个语义不同的打分方法**：

| 方法 | 公式 | 用途（钉死，避免出现无调用方的死代码） |
|---|---|---|
| `cosineScore(query, chunk)` | **纯 TF-IDF 余弦** | 混合检索的关键词路径（进入 RRF） |
| `fusionScore(query, chunk)` | 原四策略：Jaccard 0.3 + 余弦 0.5 + **分类加成 0.2** + priority 加成 | **降级路径的唯一打分器**（§3 图中"降级：fusionScore top1"），行为与改造前逐例一致 |

- **混合检索的关键词路径使用 `cosineScore`，不使用 `fusionScore`**：后者含 0.06 分类底分与 `priority*0.01` 底分，正是缺陷 1 的成因，绝不能带进 RRF 候选集
- **降级路径使用 `fusionScore` top1**：这是改造前 `AiQaServiceImpl` 的实际行为，用它才能兑现"离线行为不变"的承诺（G8 + §5.4 回归测试）
- chunk 的 token 来源：`content` + `search_terms` + 所属 `kb_document.title`
- IDF 语料基于 chunk 集合统计
- **回归保证**：`fusionScore` 路径由 `KeywordRetrieverTest` 用改造前记录的一组「问题 → top1 答案」断言逐例一致

### 5.5 `HybridRetriever`（`com.freshman.rag.HybridRetriever`）

```java
RetrievalResult retrieve(String question, int topK);
// com.freshman.rag.dto.RetrievalResult
// { List<ScoredChunk> chunks, boolean hasQualifiedMaterial, String gateMode, double topScore }
```

**`topK` 语义（跨规格契约）**：`retrieve()` **尊重传入的 `topK`**，仅钳制到 `[1, top-k-vector]`（默认上限 20）。配置 `top-k-final=5` **只约束 RAG 生成路径往 prompt 里注入的材料条数**，不约束检索层 —— 因此子系统 B 的 `search_knowledge(top_k=10)` 能真的拿到 10 条。

#### 5.5.1 三种分数的语义（不可混用）

| 分数 | 含义 | 取值 | 参与门限 |
|---|---|---|---|
| `vectorCosine` | 向量路径**原始余弦** | 0 ~ 1 | ✅（`gate_mode=vector`） |
| `keywordScore` | 关键词路径**原始 TF-IDF 余弦** | 0 ~ 1 | ✅（`gate_mode=keyword`） |
| `rrfScore` | 融合分 `Σ 1/(k + rank)`，`k=60` | ≈ 0.016 ~ 0.033 | ❌ **仅排序** |

> ⚠️ **RRF 分丢弃了绝对相似度**。把 RRF 分与 0.35 门限比较会导致**永远拒答**、`relative-floor` 静默失效 —— 这是必须在实现前钉死的语义。

**`ScoredChunk` 字段定义（跨规格契约，子系统 B 的 `search_knowledge` 依赖）**：

```java
package com.freshman.rag.dto;

public class ScoredChunk {
    private Long   chunkId;        // kb_chunk.id
    private Long   documentId;     // kb_document.id
    private String sourceType;     // 见 §5.7
    private Long   sourceId;
    private int    chunkIndex;
    private String title;          // kb_document.title
    private String urlPath;        // 可为 null
    private String content;        // chunk 正文
    private String snippet;        // 截断至 200 字的摘要（引用卡片与工具结果用）
    private Double vectorCosine;   // 可空
    private Double keywordScore;   // 可空
    private double rrfScore;       // 仅排序
}
```

- 子系统 B 只允许读 `title / urlPath / snippet / sourceType / sourceId` 构造工具结果；**不得把 `rrfScore` 当相似度展示**
- `HybridRetriever` 全限定名 `com.freshman.rag.HybridRetriever`；入口 `RetrievalResult retrieve(String question, int topK)`
- 子系统 B 的"无材料"判定用 **`hasQualifiedMaterial == false`**（而不是 `chunks.isEmpty()`）—— 两者语义不同，前者含门限判定

#### 5.5.2 门限规则（每次请求只有一种 `gate_mode`）

| 条件 | `gate_mode` | 绝对门限 | 相对门限 |
|---|---|---|---|
| 向量索引可用 **且** 问题 embedding 成功 | `vector` | 向量路径 top1 的 `vectorCosine` ≥ `min-score`(0.35) | 候选自身 `vectorCosine` ≥ `relative-floor`(0.6) × 向量路径 top1 的 `vectorCosine` |
| 索引为空 / embedding 失败 / 主动降级 | `keyword` | 关键词路径 top1 的 `keywordScore` ≥ `keyword-min-score`(0.25) | 候选自身 `keywordScore` ≥ `relative-floor`(0.6) × 关键词路径 top1 的 `keywordScore` |

规则细节（**含 v2 未定义的空值规则**）：

1. 门限**只作用于当前 `gate_mode` 对应的那一种原始分**
2. **空值候选的处置**：`gate_mode=vector` 时，只有 `vectorCosine != null` 的候选**可以进入材料集**；仅由关键词路径命中的候选（`vectorCosine == null`）**不进入材料集**，但**保留在 RRF 排序结果与日志中**（便于分析"关键词命中了但向量没命中"）。`gate_mode=keyword` 时对称处理
3. `gate_mode=vector` 时**不回退**到关键词门限（避免两套口径混用）；若向量门限全部不过 → 直接拒答
4. 不满足相对门限的候选剔除出材料集，但仍在日志记录
5. `gate_mode` 与 `top_score` 一并写入 `ai_retrieval_log`，保证"拒答依据"可追溯

#### 5.5.3 融合与去重

- **RRF**：`rrfScore(d) = Σ_paths 1 / (rrf-k + rank_path(d))`，`rrf-k=60`。选 RRF 而非加权求和：无需为两条路径标定权重、对分数量纲鲁棒
- **同文档去重**：同一 `document_id` 最多保留 `max-per-document`(2) 条，按 `rrfScore` 取高

#### 5.5.4 与现有抽象的衔接（修复"两套检索实现"）

`AiQaService.retrieveContext(question, topK)` **保留签名**，实现改为 `HybridRetriever` 薄封装。其唯一调用方 `DeepSeekChatService.java:142`（`useRag=true` 分支）无需改动即获得新检索能力 —— 消除面试文档点名的"两条 RAG 路径行为不一致"。

### 5.6 `RagService` 与 `LlmClient`

```java
public interface LlmClient {
    LlmResult complete(String systemPrompt, String userMessage);
    // LlmResult: { boolean success, String content, int totalTokens, long costMs, String error }
}
```

- 实现 `OpenAiCompatibleLlmClient`：**HTTP 实现从 `AiQaServiceImpl.callLlmApi()` 抽出**，复用现有 `app.ai.llm.*` 配置（`provider=qwen`、`qwen-plus`）
- **可注入是硬要求**：G4 需断言"拒答时 LLM 调用次数 = 0"，测试注入 `RecordingLlmClient`（计数）与 `ScriptedLlmClient`（固定答案）
- 与子系统 B 的 `DeepSeekClient` **有意独立**：配置命名空间不同、请求体不同（Agent 需带 `tools`）、失败语义不同（本设计降级到本地引擎，Agent 降级到无工具 chat）。统一为公共客户端属后续独立技术债

**落库职责（v2 未明确，现钉死）**：

| 组件 | 职责 |
|---|---|
| `AiQaServiceImpl.chat(question, sessionId, ip, userId)` | **签名与落库职责不变** —— 仍由它负责 `saveHistory(...)` 写 `ai_chat_history`（它持有 `chatHistoryMapper` 与 `userId`/`ip`）；内部改为委托 `RagService.ask(question, sessionId)` 获取 `ChatResponse` |
| `RagService.ask(String question, String sessionId)` | 只做 **检索 + 门限 + 生成 + 引用解析 + 写 `ai_retrieval_log`**，**不写 `ai_chat_history`** |

因此 `RagService` 的入参只需 `question` 与 `sessionId`（`ai_retrieval_log.session_id` 够用），不需要 `userId`/`ip` —— 避免了 DTO 与现有签名冲突。

**Prompt 模板**：

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
```

> 材料块中的"相似度"取该 chunk 在**当前 `gate_mode` 下的原始分**，**绝不展示 `rrfScore`**。人设与现有 `/ai-chat` 页面一致（`AiQaServiceImpl.java:708`）。

**引用处理**：解析 `[n]` → `citations[]`（`documentId`/`title`/`urlPath`/`chunkId`/`snippet`/`score`）；**幻觉引用检测**：引用了不存在的编号 → 剔除 + `log.warn` + 该次回答标 `degraded=true`；未被引用的材料照常返回（前端折叠展示）

**拒答路径**：`hasQualifiedMaterial=false` → `unknown=true` 固定话术 + `getQuickQuestions()` 推荐，**不调用 LLM**，`generate_ms` 记 `NULL`

**降级路径**：`LlmClient` 失败/未配置 → 用 `KeywordRetriever.fusionScore()` 的 top1 答案，`degraded=true`，答案前缀"（离线降级回答）"

### 5.7 `KnowledgeIndexer` 与来源映射

```java
IndexReport rebuildAll(boolean force);
```

流程：`抽取（DocumentSource 实现）` → `清洗（去 HTML/折叠空白）` → `切分` → `content_hash 比对（未变跳过）` → `Embedding` → `落 kb_chunk` → `VectorIndex.rebuild()`

**来源映射表（正文列已按实体/DTO 字段核实，这是 10 个 `DocumentSource` 实现的依据）**：

| # | `source_type` | 来源表 | `title` | **正文列（组成文档正文）** | `url_path` | 切分策略 |
|---|---|---|---|---|---|---|
| 1 | `ai_knowledge` | `ai_knowledge` | `question` | `question` + `answer` | 无 | 不切分（§4.3） |
| 2 | `guide_faq` | `guide_faq` | `question` | `question` + `answer` | `/guide/faq` | 不切分（§4.3） |
| 3 | `guide_registration_step` | `guide_registration_step` | `title` | `title` + `description` + `location` + `requiredMaterials` + `tips` | `/guide/registration` | 按 `stepNo` 边界切分 |
| 4 | `guide_major` | `guide_major` | `name` | `college` + `degree` + `duration` + `description` + `courses` + `careerProspect` + `features` | `/guide/majors` | 按段落切分 |
| 5 | `life_dormitory` | `life_dormitory` | `name` | `buildingNo` + `type` + `roomType` + `facilities` + `description` + `fee` | `/life/dormitory` | 按段落切分 |
| 6 | `life_cafeteria` | `life_cafeteria` | `name` | `location` + `floors` + `openingHours` + `description` + `specialties` | `/life/cafeteria` | 按段落切分 |
| 7 | `life_club` | `life_club` | `name` | `category` + `description` + `memberCount` + `recruitInfo` + `activityTime` + `location`（**排除 `president`/`contact`：个人信息**） | `/life/clubs`（复数） | 按段落切分 |
| 8 | `life_activity` | `life_activity` | `title` | `description` + `category` + `location` + `organizer` + `startTime` + `endTime` | `/life/activities` | 按段落切分 |
| 9 | `campus_building` | `campus_building` | `name` | `category` + `description` + `address` + `floors` + `openingHours` + `tags` | `/campus/{id}` | 按段落切分 |
| 10 | `sys_news` | `sys_news` | `title` | `summary` + `content` | `/news/{id}` | 按段落切分 |
| — | `guide_teacher` | `guide_teacher` | — | — | — | ❌ **排除**：含 `email` 等个人信息，不进第三方 LLM 请求 |

- 注：`campus_building` 列表页 `/campus` **是存在的**（`CampusController.java:43`），但引用定位到具体建筑更合适，故仍取 `/campus/{id}`
- `url_path` 为空的来源（`ai_knowledge`），前端引用卡片降级为「标题 + 展开原文」，不生成跳转链接
- 返回 `IndexReport{documentCount, chunkCount, embeddedCount, skippedCount, failedCount, costMs}`

### 5.8 `RagEvalRunner`（评估）

评估集：`docs/rag/eval-set.jsonl`，**期望值用稳定 ref 键**（`kb_chunk.id` 是自增的，重建后会漂移）：

```json
{"id":1,"question":"宿舍有空调吗","expected_refs":["ai_knowledge:27:0"],"answerable":true}
{"id":2,"question":"报到流程是怎样的","expected_refs":["ai_knowledge:2:0","guide_registration_step:1:0"],"answerable":true}
{"id":3,"question":"学校有没有高尔夫球场","expected_refs":[],"answerable":false}
```

- ref 格式 `{source_type}:{source_id}:{chunk_index}`；评估器解析为**当前** `kb_chunk.id` 再比对；**解析不到该 ref 时报错而非静默跳过**（防语料变动后指标虚高）
- **负样本自检（v3 新增）**：`answerable=false` 的题目必须先在语料上跑一次检索确认"无合格材料"；若某条负样本实际有材料，**评估直接失败并提示修正标注**。这避免了标注错误把"拒答正确率"变成假指标（例如原示例"学校有没有游泳池"就与 `ai_knowledge.id=74` 的运动场所条目存在覆盖重叠，已替换）
- 24 条，其中**至少 5 条 `answerable=false`**
- 指标：`Hit@1`、`Hit@3`、`MRR`、拒答正确率、引用准确率
- 载体：`RagEvalRunnerTest`（`@Tag("eval")`，被 surefire 排除）+ `GET /admin/ai/eval`
- **必须产出改造前 vs 改造后对比表**，写入 `docs/rag/eval-report.md`

---

## 6. 配置项（全部外部化，修掉缺陷 4）

```yaml
app:
  ai:
    rag:
      enabled: true
      top-k-vector: 20          # 向量召回候选数，同时是 retrieve(topK) 的上限
      top-k-keyword: 20
      top-k-final: 5            # 仅约束 RAG 生成路径注入 prompt 的材料数
      min-score: 0.35           # vector 模式绝对门限（原始余弦）
      keyword-min-score: 0.25   # keyword 模式绝对门限（原始 TF-IDF 余弦）
      relative-floor: 0.6
      rrf-k: 60                 # 仅影响排序
      max-per-document: 2
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

启动校验：`size > min-size > 0`、`overlap < size`、`relative-floor ∈ (0,1]`、`top-k-final ≤ min(top-k-vector, top-k-keyword)`、`min-score ∈ (0,1]`、`dimensions > 0`。非法配置**启动即失败并给出明确报错**。

> **注册方式**：项目**没有** `@ConfigurationPropertiesScan`（`FreshmanApplication` 仅 `@MapperScan("com.freshman.mapper")`），故 `RagProperties` 必须写成 `@Component + @ConfigurationProperties(prefix = "app.ai.rag")`。

---

## 7. 接口与前端

### 7.1 接口

| 方法 | 路径 | 权限（实际） | 说明 |
|---|---|---|---|
| POST | `/api/ai/chat` | **`authenticated`** | `SecurityConfig.java:70`；签名不变，响应新增字段 |
| GET | `/api/ai/history` 等 | `authenticated` | 不变 |
| POST | `/admin/ai/reindex` | `hasRole("ADMIN")` | `SecurityConfig.java:84` 已 gate `/admin/**` |
| GET | `/admin/ai/eval` | `hasRole("ADMIN")` | **必须加 `@ResponseBody` + `produces="text/markdown;charset=UTF-8"`**，因为 `AdminController` 是 `@Controller` 返回视图名，否则会去找不存在的模板 |
| GET | `/admin/ai/retrieval-stats` | `hasRole("ADMIN")` | 同上，返回 JSON（`@ResponseBody`） |

三个新端点挂在 `AdminController`，**无需改 `SecurityConfig`**。`ChatResponse` 新增 `citations`/`degraded`/`costMs`，旧前端忽略新字段不受影响。

### 7.2 前端 `ai-chat.html`

- 答案下方「参考来源」折叠块：`序号｜标题｜相似度`，有 `url_path` 的可点击跳转，无链接的展开原文
- 置信度可视化（现有 `confidence` 终于有用）
- 拒答样式与普通回答区分（不同底色 + "知识库暂未收录"标签）
- 降级回答显示"离线降级"标签

---

## 8. 测试与评估计划（三层，含分级排除）

> **测试基建从零建立**：`src/test` 不存在，M1 首个任务创建目录与 surefire 配置。
> **必须补 surefire 配置**：`pom.xml` 无 surefire 配置，仅 `@Tag` **不排除任何测试**。需加插件配置：
> `<configuration><excludedGroups>eval,it</excludedGroups></configuration>`
> （**仅插件配置，不新增依赖，G9 成立**）

| 层 | Tag | 依赖 | 是否在 `mvn test` 中执行 |
|---|---|---|---|
| 单元测试 | 无 | 纯 JVM（Mockito mock mapper） | ✅ 执行 |
| **集成测试** | `@Tag("it")` | **真实 MySQL**（`campus_building` 种子等真实数据） | ❌ 排除，手动跑 |
| 付费评估 | `@Tag("eval")` | 真实 Embedding/LLM API | ❌ 排除，手动跑 |

**为什么需要 `it` 层（v3 新增）**：有两条 DoD 断言在纯 mock 下**不可观测** ——
(a) 「§7 全部地点名可解析」若用 mock 的 mapper，则永远为真，无法约束"坐标不得编造"；
(b) 「注入尝试被拒/匹配不到数据」需要真实数据库才能观测到"0 行"。
故这两条下沉到 `it` 层用真实 MySQL 断言。

| 测试类 | 层 | 覆盖 |
|---|---|---|
| `ChunkSplitterTest` | 单元 | 空文本/纯空白/超长无标点/仅标题/碎片合并/overlap 句边界/非法配置 |
| `EmbeddingClientTest` | 单元 | 本地 HTTP stub：正常批量、**返回乱序按 `index` 对齐**、超时重试、单条失败隔离 |
| `VectorIndexTest` | 单元 | 点积=余弦、TopK 最小堆、热重建原子性、空索引退化、**只加载 status=1** |
| `KeywordRetrieverTest` | 单元 | `cosineScore` 不含量纲污染；`fusionScore` 与改造前**逐例一致** |
| `HybridRetrieverTest` | 单元 | RRF 仅排序、**门限只作用于对应 gate_mode 的原始分**、**空值候选不进材料集**、`gate_mode` 切换、去重、全部不过时拒答 |
| `RagServiceTest` | 单元 | 拒答时 `RecordingLlmClient.callCount == 0`、引用解析、**幻觉引用剔除**、LLM 失败降级走 `fusionScore` |
| `KnowledgeIndexerTest` | 单元 | 增量跳过、失败 chunk 标 `status=2`、`url_path` 映射正确 |
| `RagIndexIntegrationTest` | **it** | 连真实 MySQL：10 个来源全部可抽取、chunk 落库、向量非空且维度=1024 |
| `RagEvalRunnerTest` | **eval** | 真实 API，产出指标；ref 解析失败报错；负样本自检 |

集成验证（我本地执行，**外部 API 必须走 Java，`curl` 仅用于 localhost 明文**）：

1. `mvn test`（按 §0 的构建命令，JDK 21 + 可写本地仓库）→ 单元层全绿且不触发 `it`/`eval`
2. 连本地 MySQL 执行 schema 脚本 → `POST /admin/ai/reindex` → 校验 `kb_chunk` 有向量
3. 启动应用 → `curl http://localhost:8080/api/ai/chat` 验证：正常题带引用、无关题拒答、断 key 降级

---

## 9. 明确不做（YAGNI）

| 不做 | 理由 |
|---|---|
| 向量库（Milvus / pgvector / Redis） | 千级 chunk 下暴力检索 <5 ms，引入运维成本与依赖，收益为负 |
| Rerank 模型（gte-rerank） | 属"进阶档"；RRF + 双门限已解决噪声污染 |
| 查询改写 / HyDE / 多查询 | 同上，且成倍增加 API 成本 |
| 流式输出（SSE） | 与现有 Thymeleaf + `fetch` 架构改动面大，演示收益低 |
| 知识库 CRUD 管理页面 | 只提供 reindex 端点 |
| PDF/Word 解析、多模态 | 无此数据源需求 |
| 统一公共 LLM 客户端（与 Agent 共用） | 见 §5.6，两个有意的独立抽象 |
| 会话管理统一重构 | 独立技术债 |
| BM25 替换 TF-IDF | 会改变关键词召回行为，破坏回归基线 |
| `guide_teacher` 纳入知识库 | 含个人信息，不进第三方 LLM 请求 |

---

## 10. 风险与缓解

| 风险 | 影响 | 缓解 |
|---|---|---|
| ~~Embedding / 构建链路不可用~~ | — | ✅ **均已实测通过**（HTTP 200 / 1024 维 / BUILD SUCCESS），风险退役 |
| Embedding 成本/限流 | 重建慢、超额 | `content_hash` 增量跳过 + 批量 25 条 + 失败隔离 |
| `application.yml` 明文 API Key 已入库 | 安全 | 改用 `${DASHSCOPE_API_KEY:}`；**建议轮换该 key** |
| JDK 版本用错导致 Lombok 静默失效 | 编译报"找不到符号"，易误判为代码错误 | 固定用 **JDK 21**（见构建说明坑 4） |
| Maven 仓库在工作区外被沙箱拒写 | 构建失败 | 用 `-Dmaven.repo.local` 指向可写仓库（见坑 1） |
| 本环境 curl/.NET 无法建 TLS | 验证手段受限 | 外部 API 走 Java；`curl` 仅 localhost |
| 暴力检索规模上限 | 数据增长后变慢 | 类注释写明迁移路径；`ai_retrieval_log.retrieval_ms` 提供实测依据 |
| 索引加载失败导致检索全空 | 功能静默退化 | `log.warn` + 自动转 `gate_mode=keyword` + 管理端点可查索引状态 |

---

## 11. 里程碑

| 里程碑 | 内容 | 完成判据 |
|---|---|---|
| **M0 前置验证** ✅ | embedding 可用性、构建链路、TLS 结论 | 见 §0 |
| **M1 数据层** | 测试基建 + surefire；DDL 脚本；`ChunkSplitter`；`EmbeddingClient`；`VectorIndex`；`KnowledgeIndexer`（10 个来源）；reindex 端点 | chunk 与向量落库；单元层 + `RagIndexIntegrationTest` 全绿 |
| **M2 检索与生成** | `KeywordRetriever`（双打分）、`HybridRetriever`、`LlmClient`、`RagService`、拒答与降级、`ChatResponse` 扩展、`retrieveContext` 委托 | `HybridRetrieverTest`/`RagServiceTest`/`KeywordRetrieverTest` 全绿；`curl` 验证三类响应 |
| **M3 评估与可观测** | 评估集（稳定 ref + 负样本自检）、`RagEvalRunner`、`ai_retrieval_log`、管理端点 | `eval-report.md` 产出对比表 |
| **M4 前端与收尾** | `ai-chat.html` 来源折叠块/置信度/拒答样式；文档与话术更新 | 人工走查 4 个场景通过 |

---

## 12. 新增与修改文件

### 12.1 新增

```
com.freshman.rag
  RagProperties.java              @Component + @ConfigurationProperties(app.ai.rag) + 启动校验
  ChunkSplitter.java
  EmbeddingClient.java
  VectorIndex.java                + ChunkMeta
  KeywordRetriever.java           cosineScore + fusionScore
  HybridRetriever.java
  LlmClient.java                  接口
  OpenAiCompatibleLlmClient.java
  RagService.java
  KnowledgeIndexer.java           + DocumentSource 接口与其 10 个实现
  RagEvalRunner.java
  dto/Chunk.java / ScoredChunk.java / RetrievalResult.java / Citation.java / IndexReport.java
src/test/java/com/freshman/rag/   §8 的 9 个测试类 + RecordingLlmClient / ScriptedLlmClient
docs/sql/2026-09-18_rag_schema.sql
docs/rag/eval-set.jsonl
docs/rag/eval-report.md
```

### 12.2 修改

| 文件 | 修改内容 |
|---|---|
| `AiQaService.java` | `ChatRequest`/`ChatResponse` 增加 `citations`/`degraded`/`costMs` 字段；`retrieveContext` 实现改为委托（**签名不变**） |
| `AiQaServiceImpl.java` | `chat()` 改为委托 `RagService.ask()` 并保留自身 `saveHistory` 落库；`callLlmApi()` 抽出为 `OpenAiCompatibleLlmClient`；分词/同义词/融合评分抽出到 `ChineseTokenizer` + `KeywordRetriever`（**算法不变**） |
| `AiQaController.java` | 透传 `citations`/`degraded` |
| `AdminController.java` | 新增 3 个 `/admin/ai/**` 端点（`@ResponseBody`） |
| `templates/ai-chat.html` | 来源折叠块、置信度、拒答样式、降级标签 |
| `src/main/resources/application.yml` | 新增 `app.ai.rag.*`；`api-key` 改环境变量兜底 |
| `pom.xml` | **仅**新增 surefire `<excludedGroups>eval,it</excludedGroups>` |

---

## 13. 完成定义（DoD）

- [ ] `docs/sql/2026-09-18_rag_schema.sql` 含 `IF NOT EXISTS`，可在现有库上**重复执行**
- [ ] `pom.xml` 依赖列表未变（G9）；仅新增 surefire 配置，且 `mvn test` **不触发** `it`/`eval`
- [ ] §8 单元层全部通过（含 `fusionScore` 改造前后回归一致）
- [ ] `RagIndexIntegrationTest`（it 层，真实 MySQL）通过
- [ ] §2.1 的 G1–G10 逐条有证据
- [ ] `docs/rag/eval-report.md` 含改造前后 Hit@1/Hit@3/MRR/拒答正确率对比
- [ ] 断 key 场景实测通过（降级走 `fusionScore`、不报错、不阻断启动）
- [ ] 拒答题实测**未调用 LLM**（`RecordingLlmClient.callCount==0` 且 `ai_retrieval_log.generate_ms IS NULL`）
- [ ] `gate_mode=vector` 与 `gate_mode=keyword` 两条门限路径均有实测证据
- [ ] 评估集负样本自检生效（有材料时会失败并提示修正标注）
