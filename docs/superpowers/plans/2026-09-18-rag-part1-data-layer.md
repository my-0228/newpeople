# AI 问答 RAG 化 · 计划一：数据层（M1）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 建立 RAG 的数据地基 —— 把 10 张业务表抽取成知识文档、切分成 chunk、调用百炼 `text-embedding-v3` 生成 1024 维向量并落库，启动时在内存里加载成可检索的向量索引，并提供管理员重建端点。

**Architecture:** 新增独立包 `com.freshman.rag`，与现有 `AiQaServiceImpl`（保留为降级引擎）解耦。数据流是单向管道：`DocumentSource`（10 个实现，每个负责一张业务表）→ `ChunkSplitter` → `EmbeddingClient` → `kb_chunk` 落库 → `VectorIndex` 内存索引。全程不修改任何现有表结构，不新增 Maven 依赖。

**Tech Stack:** Spring Boot 3.2.5 / Java 17 / MySQL 8 / MyBatis-Plus 3.5.5 / Hutool 5.8.27（JSON）/ JDK `java.net.http.HttpClient` / JUnit 5 + Mockito（`spring-boot-starter-test`，已在 pom）

**规格来源：** `docs/superpowers/specs/2026-09-18-ai-qa-rag-design.md`（第 4、5.1–5.3、5.7、6、12 节）
**构建环境：** `docs/superpowers/specs/2026-09-18-build-verification-notes.md` —— **必须按其中命令构建，三个坑不绕开就编不过**

---

## 环境约定（每个任务的验证命令都基于此）

```powershell
# 项目根目录：D:\LinkHub\newpeople
$env:JAVA_HOME = "D:\PyCharm 2025.1.2\jbr"          # JDK 21，绝不能用 JDK 25
$mvn = "$env:USERPROFILE\.m2\wrapper\dists\apache-maven-3.9.12-bin\5nmfsn99br87k5d4ajlekdq10k\apache-maven-3.9.12\bin\mvn.cmd"
$repo = "$env:USERPROFILE\.m2\repository"
$MVN = "$mvn -s docs\build\maven-settings.xml `"-Dmaven.repo.local=$repo`" -B"

# 单元层（默认，免费）
Invoke-Expression "$MVN test"
# 集成层（需要真实 MySQL）
Invoke-Expression "$MVN test -Dtest.excludedGroups=eval"
# 编译前若报 `inputFiles.lst: Input length = 1`，先删陈旧构建状态
Remove-Item -Recurse -Force target\maven-status -ErrorAction SilentlyContinue
```

**测试分层约定**：单元测试不加 Tag（`mvn test` 会跑）；连真实 MySQL 的加 `@Tag("it")`；调真实付费 API 的加 `@Tag("eval")`。

---

## 文件结构（本计划涉及的全部文件）

| 文件 | 职责 |
|---|---|
| `docs/sql/2026-09-18_rag_schema.sql` | 3 张新表的幂等 DDL |
| `src/test/java/com/freshman/it/SchemaApplyIT.java` | 执行 DDL 脚本并断言表结构（it 层） |
| `src/main/java/com/freshman/entity/KbDocument.java` | `kb_document` 实体 |
| `src/main/java/com/freshman/entity/KbChunk.java` | `kb_chunk` 实体 |
| `src/main/java/com/freshman/entity/AiRetrievalLog.java` | `ai_retrieval_log` 实体 |
| `src/main/java/com/freshman/mapper/KbDocumentMapper.java` | Mapper（**必须在 `com.freshman.mapper`**） |
| `src/main/java/com/freshman/mapper/KbChunkMapper.java` | 同上 |
| `src/main/java/com/freshman/mapper/AiRetrievalLogMapper.java` | 同上 |
| `src/main/java/com/freshman/rag/RagProperties.java` | `app.ai.rag.*` 配置 + 启动期校验 |
| `src/main/java/com/freshman/rag/ChunkSplitter.java` | 结构感知切分 + overlap |
| `src/main/java/com/freshman/rag/EmbeddingClient.java` | 百炼 Embedding 调用、批量、重试、归一化 |
| `src/main/java/com/freshman/rag/VectorIndex.java` | 内存向量索引、暴力余弦 TopK、热重建 |
| `src/main/java/com/freshman/rag/KnowledgeIndexer.java` | 抽取→切分→向量化→落库→重建索引 |
| `src/main/java/com/freshman/rag/source/DocumentSource.java` | 文档来源接口 |
| `src/main/java/com/freshman/rag/source/*Source.java` | 10 个来源实现 |
| `src/main/java/com/freshman/rag/dto/Chunk.java` | 切分结果 |
| `src/main/java/com/freshman/rag/dto/ScoredChunk.java` | 检索结果（**跨规格契约**，子系统 B 依赖其字段） |
| `src/main/java/com/freshman/rag/dto/IndexReport.java` | 重建报告 |
| `src/main/java/com/freshman/rag/VectorIndexLoader.java` | 启动时加载索引（`ApplicationRunner`） |
| `src/main/java/com/freshman/controller/AdminController.java` | **修改**：新增 `/admin/ai/reindex` |
| `src/main/resources/application.yml` | **修改**：新增 `app.ai.rag.*` |
| `pom.xml` | **修改**：新增 surefire 配置（仅插件，不加依赖） |
| `src/test/java/com/freshman/rag/*Test.java` | 单元测试 4 个 |
| `src/test/java/com/freshman/it/RagIndexIT.java` | 端到端数据层验证（it 层） |

---

### Task 1: 测试基建与可复现构建

**Files:**
- Create: `src/test/java/com/freshman/BuildSmokeTest.java`
- Create: `src/test/resources/.gitkeep`（保持目录被 git 跟踪）
- Modify: `pom.xml`（`<build><plugins>` 内新增 surefire 配置）

- [ ] **Step 1: 写一个必然通过的冒烟测试（先验证测试设施本身可用）**

创建 `src/test/java/com/freshman/BuildSmokeTest.java`：

```java
package com.freshman;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** 冒烟测试：仅证明测试设施（surefire + JUnit5）可用，无业务含义。 */
class BuildSmokeTest {

    @Test
    void testInfrastructureWorks() {
        assertTrue(true, "测试设施应可用");
    }
}
```

- [ ] **Step 2: 运行，确认当前 surefire 能跑（此时还没加 excludedGroups 配置）**

```powershell
Invoke-Expression "$MVN test -Dtest=BuildSmokeTest"
```
Expected: `Tests run: 1, Failures: 0, Errors: 0, Skipped: 0` + `BUILD SUCCESS`

- [ ] **Step 3: 加 surefire 分层排除配置（属性占位符，保证命令行可覆盖）**

在 `pom.xml` 的 `<properties>` 中加：

```xml
<test.excludedGroups>eval,it</test.excludedGroups>
```

在 `<build><plugins>` 中（`spring-boot-maven-plugin` 旁边）加：

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-surefire-plugin</artifactId>
    <configuration>
        <!-- 用属性占位符而非字面量：字面量会被 POM 覆盖 -D，导致 it/eval 层无法手动执行 -->
        <excludedGroups>${test.excludedGroups}</excludedGroups>
    </configuration>
</plugin>
```

- [ ] **Step 4: 验证三层命令都可用**

```powershell
# 单元层：应跑 1 个测试
Invoke-Expression "$MVN test"
# 排除列表置空：应同样跑 1 个测试（证明 -D 能覆盖 POM）
Invoke-Expression "$MVN test -Dtest.excludedGroups="
```
Expected: 两条命令都是 `Tests run: 1` + `BUILD SUCCESS`

- [ ] **Step 5: 提交**

```powershell
git add pom.xml src/test
git commit -m "test: 建立测试基建与 surefire 分层排除配置"
```

---

### Task 2: RAG 数据库 schema（幂等 DDL + 实体 + Mapper）

**Files:**
- Create: `docs/sql/2026-09-18_rag_schema.sql`
- Create: `src/test/java/com/freshman/it/SchemaApplyIT.java`
- Create: `src/main/java/com/freshman/entity/KbDocument.java`、`KbChunk.java`、`AiRetrievalLog.java`
- Create: `src/main/java/com/freshman/mapper/KbDocumentMapper.java`、`KbChunkMapper.java`、`AiRetrievalLogMapper.java`

- [ ] **Step 1: 写 DDL 脚本**

创建 `docs/sql/2026-09-18_rag_schema.sql`，内容为规格 §4 的三段 `CREATE TABLE IF NOT EXISTS`（`kb_document` / `kb_chunk` / `ai_retrieval_log`），**逐字照抄规格中的 DDL**（含 `search_terms`、`gate_mode`、`top_score` 三列和各索引）。

- [ ] **Step 2: 写 it 测试：脚本可重复执行且表结构正确**

创建 `src/test/java/com/freshman/it/SchemaApplyIT.java`：

```java
package com.freshman.it;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.StreamUtils;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 执行 RAG schema 脚本，验证幂等性与表结构。需要真实 MySQL。 */
@Tag("it")
@SpringBootTest
class SchemaApplyIT {

    @Autowired
    private JdbcTemplate jdbc;

    private void applyScript() throws Exception {
        String sql = StreamUtils.copyToString(
                new java.io.FileInputStream("docs/sql/2026-09-18_rag_schema.sql"),
                StandardCharsets.UTF_8);
        for (String stmt : sql.split(";\\s*\\n")) {
            String s = stmt.trim();
            if (!s.isEmpty() && !s.startsWith("--")) {
                jdbc.execute(s);
            }
        }
    }

    @Test
    void scriptIsIdempotentAndCreatesExpectedColumns() throws Exception {
        applyScript();
        applyScript(); // 第二次执行必须不报错 —— 这就是幂等性断言

        for (String table : List.of("kb_document", "kb_chunk", "ai_retrieval_log")) {
            Integer count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.tables " +
                    "WHERE table_schema = DATABASE() AND table_name = ?", Integer.class, table);
            assertEquals(1, count, table + " 应存在");
        }

        // kb_chunk 的关键列必须存在
        for (String col : List.of("search_terms", "embedding", "content_hash", "status", "chunk_index")) {
            Integer c = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.columns " +
                    "WHERE table_schema = DATABASE() AND table_name = 'kb_chunk' AND column_name = ?",
                    Integer.class, col);
            assertEquals(1, c, "kb_chunk." + col + " 应存在");
        }
        // ai_retrieval_log 的门限可观测列
        for (String col : List.of("gate_mode", "top_score", "generate_ms")) {
            Integer c = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.columns " +
                    "WHERE table_schema = DATABASE() AND table_name = 'ai_retrieval_log' AND column_name = ?",
                    Integer.class, col);
            assertEquals(1, c, "ai_retrieval_log." + col + " 应存在");
        }
    }
}
```

- [ ] **Step 3: 运行 it 测试，确认先失败（表还不存在）**

```powershell
Invoke-Expression "$MVN test -Dtest=SchemaApplyIT -Dtest.excludedGroups=eval"
```
Expected: **FAIL** —— 因为脚本文件还没建（`FileNotFoundException`）或表不存在

- [ ] **Step 4: 建实体类**

三个实体**照抄现有 `AiKnowledge.java` 的注解风格**（`@Data @NoArgsConstructor @AllArgsConstructor @TableName(...)`，主键 `@TableId(type = IdType.AUTO)`，时间字段用 `@TableField(fill = FieldFill.INSERT)` / `INSERT_UPDATE`）。

- `KbDocument`：`id, sourceType, sourceId, title, category, urlPath, contentHash, chunkCount, status, createTime, updateTime`
- `KbChunk`：`id, documentId, chunkIndex, content, searchTerms, charStart, charEnd, contentHash, embedding, embeddingModel, dim, status, createTime, updateTime`
- `AiRetrievalLog`：`id, sessionId, question, vectorHits, keywordHits, gateMode, topScore, finalChunkIds, isUnknown, degraded, embeddingMs, retrievalMs, generateMs, createTime`

> 注意：`isUnknown`/`degraded` 在实体里用 `Integer`（与现有 `AiChatHistory.isUnknown` 一致），不要用 `Boolean`。

- [ ] **Step 5: 建 Mapper**

三个 Mapper 均为 `@Mapper public interface XxxMapper extends BaseMapper<Xxx> {}`，放在 `src/main/java/com/freshman/mapper/`（`@MapperScan("com.freshman.mapper")` 只扫这个包，放别处不会被注入）。

- [ ] **Step 6: 编译并运行 it 测试，确认通过**

```powershell
Invoke-Expression "$MVN test -Dtest=SchemaApplyIT -Dtest.excludedGroups=eval"
```
Expected: `Tests run: 1, Failures: 0` + `BUILD SUCCESS`（脚本执行两次都不报错）

- [ ] **Step 7: 提交**

```powershell
git add docs/sql src/main/java/com/freshman/entity src/main/java/com/freshman/mapper src/test/java/com/freshman/it
git commit -m "feat(rag): 新增 kb_document/kb_chunk/ai_retrieval_log 表、实体与 Mapper"
```

---

### Task 3: `RagProperties` 配置与启动期校验

**Files:**
- Create: `src/main/java/com/freshman/rag/RagProperties.java`
- Create: `src/test/java/com/freshman/rag/RagPropertiesTest.java`
- Modify: `src/main/resources/application.yml`

- [ ] **Step 1: 写失败测试（非法配置必须被拒绝）**

创建 `src/test/java/com/freshman/rag/RagPropertiesTest.java`：

```java
package com.freshman.rag;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RagPropertiesTest {

    private RagProperties valid() {
        RagProperties p = new RagProperties();
        p.getChunk().setSize(400);
        p.getChunk().setOverlap(60);
        p.getChunk().setMinSize(30);
        p.setTopKVector(20);
        p.setTopKKeyword(20);
        p.setTopKFinal(5);
        p.setMinScore(0.35);
        p.setKeywordMinScore(0.25);
        p.setRelativeFloor(0.6);
        p.getEmbedding().setDimensions(1024);
        return p;
    }

    @Test
    void validConfigPasses() {
        assertDoesNotThrow(() -> valid().validate());
    }

    @Test
    void overlapNotSmallerThanSizeRejected() {
        RagProperties p = valid();
        p.getChunk().setOverlap(400);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, p::validate);
        assertTrue(e.getMessage().contains("overlap"), "报错信息要指明是哪个配置项：" + e.getMessage());
    }

    @Test
    void minSizeNotSmallerThanSizeRejected() {
        RagProperties p = valid();
        p.getChunk().setMinSize(500);
        assertThrows(IllegalArgumentException.class, p::validate);
    }

    @Test
    void finalTopKNotExceedingRecallTopKRejected() {
        RagProperties p = valid();
        p.setTopKFinal(50);
        assertThrows(IllegalArgumentException.class, p::validate);
    }

    @Test
    void relativeFloorOutOfRangeRejected() {
        RagProperties p = valid();
        p.setRelativeFloor(1.5);
        assertThrows(IllegalArgumentException.class, p::validate);
    }
}
```

- [ ] **Step 2: 运行，确认失败（类不存在）**

```powershell
Invoke-Expression "$MVN test -Dtest=RagPropertiesTest"
```
Expected: 编译失败 `cannot find symbol: class RagProperties`

- [ ] **Step 3: 实现 `RagProperties`**

```java
package com.freshman.rag;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * RAG 配置（app.ai.rag.*）。
 * 项目没有 @ConfigurationPropertiesScan，因此必须显式 @Component 才会生效。
 * 非法配置在启动期直接失败 —— 配置错误不应表现为运行期的诡异行为。
 */
@Component
@ConfigurationProperties(prefix = "app.ai.rag")
public class RagProperties {

    private boolean enabled = true;
    private int topKVector = 20;
    private int topKKeyword = 20;
    private int topKFinal = 5;
    private double minScore = 0.35;
    private double keywordMinScore = 0.25;
    private double relativeFloor = 0.6;
    private int rrfK = 60;
    private int maxPerDocument = 2;
    private final Chunk chunk = new Chunk();
    private final Embedding embedding = new Embedding();

    public static class Chunk {
        private int size = 400;
        private int overlap = 60;
        private int minSize = 30;
        // getters / setters ...
    }

    public static class Embedding {
        private String apiUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1/embeddings";
        private String apiKey = "";
        private String model = "text-embedding-v3";
        private int dimensions = 1024;
        private int batchSize = 25;
        private int connectTimeout = 10000;
        private int readTimeout = 30000;
        // getters / setters ...
    }

    /** 启动期校验；不合法抛 IllegalArgumentException 并指明配置项。 */
    public void validate() {
        require(chunk.size > 0, "app.ai.rag.chunk.size 必须 > 0");
        require(chunk.minSize > 0 && chunk.minSize < chunk.size,
                "app.ai.rag.chunk.min-size 必须 ∈ (0, size)，当前 min-size=" + chunk.minSize + ", size=" + chunk.size);
        require(chunk.overlap >= 0 && chunk.overlap < chunk.size,
                "app.ai.rag.chunk.overlap 必须 ∈ [0, size)，当前 overlap=" + chunk.overlap + ", size=" + chunk.size);
        require(relativeFloor > 0 && relativeFloor <= 1,
                "app.ai.rag.relative-floor 必须 ∈ (0,1]，当前 " + relativeFloor);
        require(minScore > 0 && minScore <= 1, "app.ai.rag.min-score 必须 ∈ (0,1]，当前 " + minScore);
        require(keywordMinScore > 0 && keywordMinScore <= 1,
                "app.ai.rag.keyword-min-score 必须 ∈ (0,1]，当前 " + keywordMinScore);
        require(topKFinal <= Math.min(topKVector, topKKeyword),
                "app.ai.rag.top-k-final 不能大于 min(top-k-vector, top-k-keyword)，当前 "
                        + topKFinal + " > " + Math.min(topKVector, topKKeyword));
        require(embedding.dimensions > 0, "app.ai.rag.embedding.dimensions 必须 > 0");
        require(embedding.batchSize > 0 && embedding.batchSize <= 25,
                "app.ai.rag.embedding.batch-size 必须 ∈ [1,25]（百炼单次上限），当前 " + embedding.batchSize);
    }

    private static void require(boolean ok, String msg) {
        if (!ok) throw new IllegalArgumentException("RAG 配置非法：" + msg);
    }

    /** 由启动钩子调用（见 Task 8），使配置错误在启动期暴露。 */
    @jakarta.annotation.PostConstruct
    public void init() { validate(); }

    // 其余 getters / setters ...
}
```

- [ ] **Step 4: 运行，确认通过**

```powershell
Invoke-Expression "$MVN test -Dtest=RagPropertiesTest"
```
Expected: `Tests run: 5, Failures: 0`

- [ ] **Step 5: 加配置到 `application.yml`**

在 `app.ai` 下新增 `rag:` 段，**逐字照抄规格 §6 的配置块**（含把 `embedding.api-key` 写成 `"${DASHSCOPE_API_KEY:}"`）。

- [ ] **Step 6: 提交**

```powershell
git add src/main/java/com/freshman/rag/RagProperties.java src/test/java/com/freshman/rag/RagPropertiesTest.java src/main/resources/application.yml
git commit -m "feat(rag): RagProperties 配置与启动期校验"
```

---

### Task 4: `ChunkSplitter` 切分器（TDD 五步）

**Files:**
- Create: `src/main/java/com/freshman/rag/dto/Chunk.java`
- Create: `src/main/java/com/freshman/rag/ChunkSplitter.java`
- Create: `src/test/java/com/freshman/rag/ChunkSplitterTest.java`

- [ ] **Step 1: 定义 DTO 与测试骨架**

`Chunk.java`（`com.freshman.rag.dto`）：

```java
package com.freshman.rag.dto;

/** 切分结果。charStart/charEnd 相对原文，用于引用溯源。 */
public class Chunk {
    private int index;
    private String content;
    private int charStart;
    private int charEnd;
    // 全参构造 + getters ...
}
```

`ChunkSplitterTest.java` 写以下**必须存在**的用例（先全部写完，再实现）：

```java
package com.freshman.rag;

import com.freshman.rag.dto.Chunk;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ChunkSplitterTest {

    private final ChunkSplitter splitter = new ChunkSplitter();
    private ChunkSplitter.Options opts(int size, int overlap, int minSize) {
        return new ChunkSplitter.Options(size, overlap, minSize);
    }

    @Test
    void blankInputReturnsEmptyList() {
        assertTrue(splitter.split("", opts(400, 60, 30)).isEmpty());
        assertTrue(splitter.split("   \n\t ", opts(400, 60, 30)).isEmpty());
        assertTrue(splitter.split(null, opts(400, 60, 30)).isEmpty());
    }

    @Test
    void shortTextBecomesSingleChunk() {
        List<Chunk> r = splitter.split("学校有空调。宿舍条件不错。", opts(400, 60, 30));
        assertEquals(1, r.size());
        assertEquals(0, r.get(0).getCharStart());
        assertEquals(0, r.get(0).getIndex());
    }

    @Test
    void headingBoundariesSplitFirst() {
        String text = "## 一、报到流程\n携带材料到校报到。\n\n## 二、军训安排\n军训为期两周。";
        List<Chunk> r = splitter.split(text, opts(400, 60, 30));
        assertEquals(2, r.size(), "应按标题切成 2 段");
        assertTrue(r.get(0).getContent().contains("报到流程"));
        assertTrue(r.get(1).getContent().contains("军训安排"));
    }

    @Test
    void longParagraphSplitBySentenceAndNotExceedingSize() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 40; i++) sb.append("这是第").append(i).append("个句子，用于测试切分。");
        String text = sb.toString();
        List<Chunk> r = splitter.split(text, opts(100, 20, 30));
        assertTrue(r.size() > 1, "超长文本必须被切成多块");
        for (Chunk c : r) {
            assertTrue(c.getContent().length() <= 100 + 20,
                    "块长不应显著超过 size+overlap：" + c.getContent().length());
        }
    }

    @Test
    void eachChunkEndsAtSentenceBoundary() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 30; i++) sb.append("句子").append(i).append("。");
        List<Chunk> r = splitter.split(sb.toString(), opts(60, 10, 10));
        for (Chunk c : r) {
            String s = c.getContent().trim();
            assertTrue(s.endsWith("。"), "块应在句末标点处结束，实际结尾：" + s.substring(Math.max(0, s.length() - 5)));
        }
    }

    @Test
    void tinyFragmentMergedIntoNeighbour() {
        String text = "## A\n短。\n\n## B\n这是一段明显更长的正文内容，用来保证它自己不会被当成碎片合并掉。";
        List<Chunk> r = splitter.split(text, opts(400, 60, 30));
        for (Chunk c : r) {
            if (r.size() > 1) {
                assertTrue(c.getContent().length() >= 30 || r.size() == 1,
                        "除唯一块外不应出现 < min-size 的碎片");
            }
        }
    }

    @Test
    void unpunctuatedLongTextStillTerminates() {
        String text = "啊".repeat(1000); // 无任何标点
        List<Chunk> r = splitter.split(text, opts(200, 0, 30));
        assertFalse(r.isEmpty(), "硬切兜底必须产生结果，而不是死循环");
        int total = r.stream().mapToInt(c -> c.getContent().length()).sum();
        assertEquals(1000, total, "硬切不应丢字");
    }

    @Test
    void charOffsetsPointBackToOriginalText() {
        String text = "## 一、甲\n甲乙丙丁。\n\n## 二、乙\n戊己庚辛。";
        List<Chunk> r = splitter.split(text, opts(400, 0, 10));
        for (Chunk c : r) {
            String slice = text.substring(c.getCharStart(), c.getCharEnd());
            assertTrue(slice.contains(c.getContent().trim().substring(0, 2)),
                    "偏移量应能在原文中定位到该块内容");
        }
    }
}
```

- [ ] **Step 2: 运行确认失败**

```powershell
Invoke-Expression "$MVN test -Dtest=ChunkSplitterTest"
```
Expected: 编译失败（`ChunkSplitter` 不存在）

- [ ] **Step 3: 实现 `ChunkSplitter`**

实现要点（严格按规格 §5.1，六条规则按优先级）：

```java
package com.freshman.rag;

import com.freshman.rag.dto.Chunk;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Pattern;

/**
 * 结构感知切分器。
 * 规则优先级：① 标题/编号/空行边界分段 ② 段内按中文句末标点定长 ③ overlap 回退到句边界
 *            ④ < min-size 的碎片并入相邻块 ⑤ 记录原文偏移 ⑥ 单句超长时硬切兜底
 */
@Component
public class ChunkSplitter {

    /** 结构边界：Markdown 标题、中文序号、括号序号、阿拉伯序号、步骤N */
    private static final Pattern STRUCT = Pattern.compile(
            "(?m)^(#{1,6}\\s|[一二三四五六七八九十]+、|（[一二三四五六七八九十\\d]+）|\\d+[.、]|步骤\\d+)");

    private static final Set<Character> SENTENCE_END = Set.of('。', '！', '？', '；');

    public record Options(int size, int overlap, int minSize) {}

    public List<Chunk> split(String text, Options o) {
        if (text == null || text.isBlank()) return List.of();

        // ① 结构分段（保留每段在原文中的起始偏移）
        List<int[]> spans = structuralSpans(text);
        // ② 段内定长 + ③ overlap
        List<int[]> pieces = new ArrayList<>();
        for (int[] sp : spans) pieces.addAll(splitBySentence(text, sp[0], sp[1], o));
        pieces = mergeOverlap(text, pieces, o);
        // ④ 碎片合并
        pieces = mergeTiny(text, pieces, o);
        // ⑤ 产出（含偏移）
        List<Chunk> out = new ArrayList<>();
        for (int i = 0; i < pieces.size(); i++) {
            int[] p = pieces.get(i);
            String content = text.substring(p[0], p[1]).trim();
            if (content.isEmpty()) continue;
            out.add(new Chunk(out.size(), content, p[0], p[1]));
        }
        return out;
    }
    // 私有方法：structuralSpans / splitBySentence / mergeOverlap / mergeTiny / hardSplit
}
```

实现时**必须注意**：

1. `splitBySentence` 在超过 `size` 时，回退到**最近的 `。！？；`** 处切断；找不到标点才走 `hardSplit`（按 `size` 硬切）—— 这是 `unpunctuatedLongTextStillTerminates` 和 `charOffsetsPointBackToOriginalText` 能过的关键
2. `mergeOverlap` 只负责把前一块尾部 `overlap` 个字并进后一块的起点，**且起点必须回退到句边界**（若回退后为 0 则不重叠，避免死循环）
3. `mergeTiny` 把 `< minSize` 的块并入**前**一块；若是首块则并入后一块；只剩一块时直接返回
4. **硬切不丢字**：所有 span 的并集必须覆盖原文的每个非空白字符

- [ ] **Step 4: 运行全部切分测试直到全绿**

```powershell
Invoke-Expression "$MVN test -Dtest=ChunkSplitterTest"
```
Expected: `Tests run: 8, Failures: 0`（若 `unpunctuatedLongTextStillTerminates` 失败，检查是否真的走了硬切而不是提前 return）

- [ ] **Step 5: 提交**

```powershell
git add src/main/java/com/freshman/rag/ChunkSplitter.java src/main/java/com/freshman/rag/dto/Chunk.java src/test/java/com/freshman/rag/ChunkSplitterTest.java
git commit -m "feat(rag): ChunkSplitter 结构感知切分（含 overlap 与硬切兜底）"
```

---

### Task 5: `EmbeddingClient`（本地 HTTP stub 测，不打真实 API）

**Files:**
- Create: `src/main/java/com/freshman/rag/EmbeddingClient.java`
- Create: `src/test/java/com/freshman/rag/EmbeddingClientTest.java`

- [ ] **Step 1: 写测试（用 JDK 内置 `com.sun.net.httpserver.HttpServer` 当 stub，零新依赖）**

```java
package com.freshman.rag;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class EmbeddingClientTest {

    private HttpServer server;
    private RagProperties props;
    private int port;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        port = server.getAddress().getPort();
        props = new RagProperties();
        props.getEmbedding().setApiUrl("http://127.0.0.1:" + port + "/v1/embeddings");
        props.getEmbedding().setApiKey("test-key");
        props.getEmbedding().setDimensions(4);
        props.getEmbedding().setBatchSize(2);
    }

    @AfterEach
    void tearDown() { server.stop(0); }

    @Test
    void batchResultsAreReorderedByIndexField() throws Exception {
        // 故意乱序返回 index=1,0 —— 客户端必须按 index 归位
        server.createContext("/v1/embeddings", ex -> {
            String body = "{\"data\":[" +
                    "{\"index\":1,\"embedding\":[0,1,0,0]}," +
                    "{\"index\":0,\"embedding\":[1,0,0,0]}]}";
            respond(ex, 200, body);
        });
        server.start();
        EmbeddingClient client = new EmbeddingClient(props);

        List<float[]> r = client.embedBatch(List.of("甲", "乙"));

        assertEquals(2, r.size());
        assertEquals(1.0f, r.get(0)[0], 1e-6, "第 0 条应是 index=0 的向量");
        assertEquals(1.0f, r.get(1)[1], 1e-6, "第 1 条应是 index=1 的向量");
    }

    @Test
    void returnedVectorIsL2Normalized() throws Exception {
        server.createContext("/v1/embeddings", ex -> respond(ex, 200,
                "{\"data\":[{\"index\":0,\"embedding\":[3,4,0,0]}]}"));
        server.start();
        EmbeddingClient client = new EmbeddingClient(props);

        float[] v = client.embed("甲");

        assertEquals(1.0, Math.sqrt(v[0] * v[0] + v[1] * v[1]), 1e-5, "必须做 L2 归一化");
    }

    @Test
    void retriesThenSucceeds() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        server.createContext("/v1/embeddings", ex -> {
            if (calls.incrementAndGet() == 1) respond(ex, 500, "{\"error\":\"boom\"}");
            else respond(ex, 200, "{\"data\":[{\"index\":0,\"embedding\":[1,0,0,0]}]}");
        });
        server.start();
        EmbeddingClient client = new EmbeddingClient(props);

        assertDoesNotThrow(() -> client.embed("甲"));
        assertTrue(calls.get() >= 2, "应发生重试");
    }

    @Test
    void oneFailureDoesNotAbortWholeBatch() throws Exception {
        // 第一批（2 条）返回 500，第二批成功 → 批量结果里失败位为 null
        AtomicInteger calls = new AtomicInteger();
        server.createContext("/v1/embeddings", ex -> {
            if (calls.incrementAndGet() <= 3) respond(ex, 500, "{}");
            else respond(ex, 200, "{\"data\":[{\"index\":0,\"embedding\":[1,0,0,0]}]}");
        });
        server.start();
        EmbeddingClient client = new EmbeddingClient(props);

        List<float[]> r = client.embedBatch(List.of("甲", "乙"));
        assertEquals(2, r.size(), "批量结果条数必须与输入一致");
        assertTrue(r.get(0) == null || r.get(1) == null, "失败位应为 null，而不是抛异常");
    }

    private static void respond(com.sun.net.httpserver.HttpExchange ex, int code, String body) throws java.io.IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(b); }
    }
}
```

- [ ] **Step 2: 运行确认失败**

```powershell
Invoke-Expression "$MVN test -Dtest=EmbeddingClientTest"
```
Expected: 编译失败（`EmbeddingClient` 不存在）

- [ ] **Step 3: 实现 `EmbeddingClient`**

```java
package com.freshman.rag;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/**
 * 百炼 text-embedding-v3 客户端（OpenAI 兼容协议）。
 * 三个关键行为：① 按响应 index 字段对齐输入顺序（不能假设返回顺序）
 *              ② 返回前做 L2 归一化（使后续点积等价于余弦）
 *              ③ 失败隔离：单条失败置 null，不中断整批
 */
@Component
public class EmbeddingClient {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingClient.class);
    private final RagProperties props;
    private final HttpClient http;

    public EmbeddingClient(RagProperties props) {
        this.props = props;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(props.getEmbedding().getConnectTimeout()))
                .build();
    }

    /** 单条：失败返回 null（调用方负责标记 status=2）。 */
    public float[] embed(String text) {
        return embedBatch(List.of(text)).get(0);
    }

    /** 批量：按 batch-size 分组，返回与输入等长的列表，失败位为 null。 */
    public List<float[]> embedBatch(List<String> texts) {
        List<float[]> out = new ArrayList<>(Collections.nCopies(texts.size(), null));
        int bs = props.getEmbedding().getBatchSize();
        for (int from = 0; from < texts.size(); from += bs) {
            int to = Math.min(from + bs, texts.size());
            List<float[]> part = callOnce(texts.subList(from, to), from);
            for (int i = 0; i < part.size(); i++) out.set(from + i, part.get(i));
        }
        return out;
    }

    private List<float[]> callOnce(List<String> batch, int baseOffset) {
        JSONObject body = new JSONObject()
                .set("model", props.getEmbedding().getModel())
                .set("input", batch)
                .set("dimensions", props.getEmbedding().getDimensions());

        Exception last = null;
        for (int attempt = 0; attempt < 3; attempt++) {   // 1 次 + 2 次重试
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(props.getEmbedding().getApiUrl()))
                        .header("Authorization", "Bearer " + props.getEmbedding().getApiKey())
                        .header("Content-Type", "application/json")
                        .timeout(Duration.ofMillis(props.getEmbedding().getReadTimeout()))
                        .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                        .build();
                HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (resp.statusCode() != 200) throw new IllegalStateException("HTTP " + resp.statusCode() + ": " + resp.body());

                List<float[]> result = new ArrayList<>(Collections.nCopies(batch.size(), null));
                JSONArray data = JSONUtil.parseObj(resp.body()).getJSONArray("data");
                for (Object o : data) {
                    JSONObject item = (JSONObject) o;
                    int idx = item.getInt("index");
                    JSONArray vec = item.getJSONArray("embedding");
                    float[] v = new float[vec.size()];
                    for (int i = 0; i < vec.size(); i++) v[i] = vec.getFloat(i);
                    result.set(idx, l2Normalize(v));   // 按 index 归位，不依赖返回顺序
                }
                return result;
            } catch (Exception e) {
                last = e;
                log.warn("[Embedding] 第 {} 次调用失败（batchSize={}）：{}", attempt + 1, batch.size(), e.getMessage());
                sleep((attempt + 1) * 1000L);          // 1s / 2s 指数退避
            }
        }
        log.error("[Embedding] 批次最终失败，{}-{} 条将标记为待重试", baseOffset, baseOffset + batch.size());
        return new ArrayList<>(Collections.nCopies(batch.size(), null));
    }

    private static float[] l2Normalize(float[] v) {
        double sum = 0;
        for (float x : v) sum += (double) x * x;
        double norm = Math.sqrt(sum);
        if (norm == 0) return v;
        float[] r = new float[v.length];
        for (int i = 0; i < v.length; i++) r[i] = (float) (v[i] / norm);
        return r;
    }

    private static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }
}
```

- [ ] **Step 4: 运行测试直到全绿**

```powershell
Invoke-Expression "$MVN test -Dtest=EmbeddingClientTest"
```
Expected: `Tests run: 4, Failures: 0`

- [ ] **Step 5: 用真实 API 冒烟一次（唯一的付费验证，费用≈0）**

```powershell
# 临时把 api-key 指向环境变量后运行一个 it 用例（Task 6 会补），或直接用应用启动后的 reindex 验证
```
Expected: 无需在此步完成；真实调用在 Task 6 的 it 用例里一次性验证

- [ ] **Step 6: 提交**

```powershell
git add src/main/java/com/freshman/rag/EmbeddingClient.java src/test/java/com/freshman/rag/EmbeddingClientTest.java
git commit -m "feat(rag): EmbeddingClient（批量/index 对齐/L2 归一化/失败隔离）"
```

---

### Task 6: `VectorIndex` 内存向量索引

**Files:**
- Create: `src/main/java/com/freshman/rag/dto/ScoredChunk.java`
- Create: `src/main/java/com/freshman/rag/VectorIndex.java`
- Create: `src/main/java/com/freshman/rag/VectorIndexLoader.java`
- Create: `src/test/java/com/freshman/rag/VectorIndexTest.java`

- [ ] **Step 1: 定义 `ScoredChunk`（跨规格契约，字段一个都不能少）**

```java
package com.freshman.rag.dto;

/**
 * 检索结果。**跨规格契约**：子系统 B 的 search_knowledge 工具依赖
 * title / urlPath / snippet / sourceType / sourceId。
 * 三种分数的语义不可混用：vectorCosine/keywordScore 参与门限，rrfScore 仅用于排序。
 */
public class ScoredChunk {
    private Long chunkId;
    private Long documentId;
    private String sourceType;
    private Long sourceId;
    private int chunkIndex;
    private String title;
    private String urlPath;
    private String content;
    private String snippet;        // 截断至 200 字
    private Double vectorCosine;   // 可空
    private Double keywordScore;   // 可空
    private double rrfScore;
    // getters / setters ...
}
```

- [ ] **Step 2: 写测试**

```java
package com.freshman.rag;

import com.freshman.rag.dto.ScoredChunk;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class VectorIndexTest {

    private VectorIndex index;

    private void load() { /* 用 mock 的 KbChunkMapper 返回 3 条已归一化向量的记录 */ }

    @Test
    void dotProductEqualsCosineForNormalizedVectors() { /* 断言 [1,0,0,0] 与 [1,0,0,0] 得 1.0；与 [0,1,0,0] 得 0.0 */ }

    @Test
    void returnsTopKInDescendingOrder() { /* 三条向量，query 与第 2 条最相似 → 结果首条是它 */ }

    @Test
    void onlyRowWithStatusOneIsLoaded() { /* mapper 返回 status=0/1/2 各一条 → 索引里只有 status=1 的那条 */ }

    @Test
    void nullEmbeddingRowIsSkipped() { /* embedding 为 null → 不抛异常，被跳过 */ }

    @Test
    void emptyIndexReturnsEmptyListNotException() { /* 空索引 search 返回空列表 → 上层据此转 gate_mode=keyword */ }

    @Test
    void rebuildAtomicallySwapsReference() { /* rebuild 期间旧引用仍可读；rebuild 后 size 变化正确 */ }
}
```

- [ ] **Step 3: 运行确认失败** → Expected: 编译失败

- [ ] **Step 4: 实现 `VectorIndex`**

要点：

- 内部维护 `volatile Snapshot snapshot`，`Snapshot` 内含 `float[][] vectors` + `ChunkMeta[] metas`（平行数组，避免对象开销）
- `rebuild()`：从 `KbChunkMapper` 查 `status=1 AND embedding IS NOT NULL`（配合 `KbDocumentMapper` 补 title/urlPath/sourceType/sourceId）→ 解析 JSON 向量（**用 Hutool `JSONUtil.parseArray` + `getFloat(i)`，不得复用 `AiQaServiceImpl.extractJsonField()`**）→ 构建新数组 → 完成后**原子替换 `volatile` 引用**
- 解析失败或 `dim != embedding.dimensions` → 该行 `log.warn` 并跳过（对应 `AI检索日志`/`status=2` 由 Indexer 负责标记）
- `search(float[] query, int topK)`：**只加载 `status=1`**；用固定大小最小堆（`PriorityQueue`）取 TopK，返回按 `vectorCosine` 降序的 `List<ScoredChunk>`；索引为空返回空列表（不抛异常）
- 类注释写明规模结论：1000 chunk × 1024 维 ≈ 100 万次乘加，<5 ms；十万级以上需 HNSW/IVF

- [ ] **Step 5: 实现 `VectorIndexLoader`（启动时加载，失败不阻断）**

```java
@Component
public class VectorIndexLoader implements ApplicationRunner {
    @Override public void run(ApplicationArguments args) {
        try { vectorIndex.rebuild(); }
        catch (Exception e) { log.warn("[RAG] 向量索引加载失败，检索将自动退化为纯关键词模式：{}", e.getMessage()); }
    }
}
```

- [ ] **Step 6: 运行测试直到全绿**

```powershell
Invoke-Expression "$MVN test -Dtest=VectorIndexTest"
```
Expected: `Tests run: 6, Failures: 0`

- [ ] **Step 7: 提交**

```powershell
git add src/main/java/com/freshman/rag/VectorIndex.java src/main/java/com/freshman/rag/VectorIndexLoader.java src/main/java/com/freshman/rag/dto/ScoredChunk.java src/test/java/com/freshman/rag/VectorIndexTest.java
git commit -m "feat(rag): VectorIndex 内存向量索引与启动加载"
```

---

### Task 7: `KnowledgeIndexer` + 10 个 `DocumentSource`

**Files:**
- Create: `src/main/java/com/freshman/rag/source/DocumentSource.java`
- Create: `src/main/java/com/freshman/rag/source/` 下 10 个实现
- Create: `src/main/java/com/freshman/rag/dto/IndexReport.java`
- Create: `src/main/java/com/freshman/rag/KnowledgeIndexer.java`
- Create: `src/test/java/com/freshman/rag/KnowledgeIndexerTest.java`

- [ ] **Step 1: 定义接口**

```java
package com.freshman.rag.source;

import java.util.List;

/**
 * 一个业务来源 = 一个实现。索引器对每个实现调用 extract()，
 * 拿到文档级数据后再统一清洗、切分、向量化。
 */
public interface DocumentSource {

    /** 如 ai_knowledge / life_dormitory，必须是唯一的 */
    String sourceType();

    /** 引用跳转路径模板；返回 null 表示无页面（前端展开原文） */
    String urlPathTemplate();

    /** 是否需要对文档正文切分（Q/A 对返回 false，长文本返回 true） */
    boolean needsSplitting();

    List<RawDocument> extract();

    /** RawDocument: { Long sourceId, String title, String body, String searchTerms, String category } */
    record RawDocument(Long sourceId, String title, String body, String searchTerms, String category) {}
}
```

- [ ] **Step 2: 实现 10 个来源**

每个来源的**正文列与 url_path 严格照抄规格 §5.7 的映射表**。关键约束：

- `AiKnowledgeSource` / `GuideFaqSource`：`needsSplitting()` 返回 **false**；正文 = `question + "\n" + answer`；`searchTerms` = `keywords` + `synonyms`
- `GuideRegistrationStepSource`：正文 = `title + description + location + requiredMaterials + tips`
- `GuideMajorSource`：正文 = `college + degree + duration + description + courses + careerProspect + features`
- `LifeDormitorySource`：正文 = `buildingNo + type + roomType + facilities + description + fee`；`url_path = /life/dormitory`
- `LifeCafeteriaSource`：正文 = `location + floors + openingHours + description + specialties`；`url_path = /life/cafeteria`
- `LifeClubSource`：正文 = `category + description + memberCount + recruitInfo + activityTime + location`，**必须排除 `president` 与 `contact`（个人信息）**；`url_path = /life/clubs`（**复数**）
- `LifeActivitySource`：`url_path = /life/activities`
- `CampusBuildingSource`：`url_path = /campus/{id}`（模板替换）；正文含 `tags`
- `SysNewsSource`：`url_path = /news/{id}`
- **不实现 `guide_teacher`**（含 email，个人信息不进 LLM）

- [ ] **Step 3: 写索引器测试（增量跳过与失败隔离是重点）**

```java
package com.freshman.rag;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class KnowledgeIndexerTest {

    @Test
    void unchangedContentIsSkippedOnSecondRun() {
        // content_hash 未变 → 第二次 rebuildAll(false) 的 skippedCount == 首次的 chunkCount
        // 且 EmbeddingClient 的调用次数为 0（用计数用的 fake EmbeddingClient 断言）
    }

    @Test
    void forceRebuildReEmbedsEverything() { /* rebuildAll(true) → skippedCount == 0 */ }

    @Test
    void embeddingFailureIsIsolatedAndMarksStatus2() {
        // 让 fake EmbeddingClient 对其中一条返回 null
        // 断言：该 chunk.status == 2，其余 chunk.status == 1，且 rebuildAll 不抛异常
    }

    @Test
    void chunkCountAndEmbeddedCountAreReported() { /* IndexReport 字段校验 */ }
}
```

- [ ] **Step 4: 实现 `KnowledgeIndexer`**

流程（规格 §5.7）：

```
for each DocumentSource:
    raw = source.extract()
    for each doc:
        hash = sha256(doc.body)
        查 kb_document by (sourceType, sourceId)
        若已存在且 content_hash 相同且 !force → skipped，continue
        写入/更新 kb_document（title/category/urlPath/contentHash/status=1）
        删旧 kb_chunk（document_id=?）
        chunks = source.needsSplitting() ? splitter.split(body, opts) : List.of(整条)
        for each chunk: 计算 content_hash；若与库中旧 chunk 同 hash 且 !force → 复用旧 embedding，跳过向量化
        批量 embedBatch(待向量化的 content)
        落 kb_chunk（status = 向量非空 ? 1 : 2；存 embedding 用 JSONUtil.toJsonStr(float[])）
        更新 kb_document.chunk_count
index.rebuild()
返回 IndexReport{documentCount, chunkCount, embeddedCount, skippedCount, failedCount, costMs}
```

**硬性约定**：
- 向量列写 `MEDIUMTEXT`，用 `JSONUtil.toJsonStr(float[])`；**不自行格式化小数位**
- `search_terms` 只写 `searchTerms`，**绝不拼进 `content`**（避免污染向量语义）
- 单个 chunk 失败不影响其它 chunk，也不影响其它文档

- [ ] **Step 5: 运行测试直到全绿**

```powershell
Invoke-Expression "$MVN test -Dtest=KnowledgeIndexerTest"
```
Expected: `Tests run: 4, Failures: 0`

- [ ] **Step 6: 提交**

```powershell
git add src/main/java/com/freshman/rag/source src/main/java/com/freshman/rag/KnowledgeIndexer.java src/main/java/com/freshman/rag/dto/IndexReport.java src/test/java/com/freshman/rag/KnowledgeIndexerTest.java
git commit -m "feat(rag): KnowledgeIndexer 与 10 个业务文档来源"
```

---

### Task 8: reindex 端点 + 端到端 it 验证（真实 MySQL + 真实 Embedding）

**Files:**
- Modify: `src/main/java/com/freshman/controller/AdminController.java`
- Create: `src/test/java/com/freshman/it/RagIndexIT.java`

- [ ] **Step 1: 在 `AdminController` 新增端点**

```java
/** 触发 RAG 索引重建（仅 ADMIN —— /admin/** 已被 SecurityConfig 限定为 hasRole("ADMIN")） */
@PostMapping("/ai/reindex")
@ResponseBody
public Result<IndexReport> reindex(@RequestParam(defaultValue = "false") boolean force) {
    return Result.success(knowledgeIndexer.rebuildAll(force));
}
```

- [ ] **Step 2: 写 it 测试（这是本计划唯一真实付费的一步）**

```java
package com.freshman.it;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 端到端验证数据层：真实 MySQL + 真实百炼 Embedding。
 * 运行：mvn test -Dtest=RagIndexIT -Dtest.excludedGroups=eval
 * 前置：环境变量 DASHSCOPE_API_KEY 已设置（或 application.yml 中已配置 key）。
 */
@Tag("it")
@SpringBootTest
class RagIndexIT {

    @Autowired private com.freshman.rag.KnowledgeIndexer indexer;
    @Autowired private com.freshman.rag.VectorIndex vectorIndex;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void fullIndexProducesChunksAndVectors() {
        var report = indexer.rebuildAll(true);
        assertTrue(report.getDocumentCount() > 0, "应抽取到文档");
        assertTrue(report.getChunkCount() >= report.getDocumentCount(), "chunk 数应 ≥ 文档数");
        assertEquals(0, report.getFailedCount(), "首次全量重建不应有失败 chunk");

        Integer withVector = jdbc.queryForObject(
                "SELECT COUNT(*) FROM kb_chunk WHERE status = 1 AND embedding IS NOT NULL", Integer.class);
        assertTrue(withVector > 0, "应有已向量化的 chunk");

        Integer wrongDim = jdbc.queryForObject(
                "SELECT COUNT(*) FROM kb_chunk WHERE status = 1 AND dim <> 1024", Integer.class);
        assertEquals(0, wrongDim, "向量维度应为 1024");

        assertEquals(withVector, vectorIndex.size(), "内存索引条数应与库中已向量化条数一致");
    }

    @Test
    void aiKnowledgeRowsAreStoredAsSingleChunk() {
        Integer multi = jdbc.queryForObject(
                "SELECT COUNT(*) FROM (SELECT d.id FROM kb_document d JOIN kb_chunk c ON c.document_id = d.id " +
                "WHERE d.source_type = 'ai_knowledge' GROUP BY d.id HAVING COUNT(*) > 1) t", Integer.class);
        assertEquals(0, multi, "Q/A 对不应被切分成多块");
    }

    @Test
    void guideTeacherIsExcluded() {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM kb_document WHERE source_type = 'guide_teacher'", Integer.class);
        assertEquals(0, n, "含个人信息的 guide_teacher 不得进入知识库");
    }
}
```

- [ ] **Step 3: 运行 it 测试（唯一真实付费调用）**

```powershell
$env:DASHSCOPE_API_KEY = "<你的阿里 key>"
Invoke-Expression "$MVN test -Dtest=RagIndexIT -Dtest.excludedGroups=eval"
```
Expected: `Tests run: 3, Failures: 0` + `BUILD SUCCESS`（首次全量约 100 个文档 → 约 5 次批量调用）

- [ ] **Step 4: 验证 `mvn test`（默认层）不触发付费**

```powershell
Invoke-Expression "$MVN test"
```
Expected: 只跑单元层，**不出现** `RagIndexIT`；`BUILD SUCCESS`

- [ ] **Step 5: 确认依赖未增加**

```powershell
git diff --stat HEAD~8 -- pom.xml
```
Expected: 只有 surefire 插件配置与 `test.excludedGroups` 属性，**`<dependencies>` 无变化**

- [ ] **Step 6: 提交**

```powershell
git add src/main/java/com/freshman/controller/AdminController.java src/test/java/com/freshman/it/RagIndexIT.java
git commit -m "feat(rag): /admin/ai/reindex 端点与端到端索引验证"
```

---

## 计划一完成定义（DoD）

- [ ] `mvn test`（单元层）全绿，且**不触发** `it`/`eval` 层
- [ ] `mvn test -Dtest.excludedGroups=eval`（含 it 层）全绿
- [ ] `kb_document` / `kb_chunk` 有数据，`status=1` 的 chunk 向量维度为 1024
- [ ] `ai_knowledge` 与 `guide_faq` 的每条 QA 恰好 1 个 chunk
- [ ] `guide_teacher` 零记录
- [ ] `VectorIndex.size()` 与库中已向量化 chunk 数一致
- [ ] 索引重建失败**不阻断应用启动**（手动验证：临时清空 `kb_chunk` 后启动，应用正常起来且日志有 warn）
- [ ] `pom.xml` 的 `<dependencies>` 无变化（零新依赖）

## 交给计划二的内容（不属本计划）

`KeywordRetriever`（双打分）、`HybridRetriever`（RRF + 双门限 + 空值候选规则）、`LlmClient`、`RagService`（引用溯源/拒答/降级）、`ai-chat.html` 前端、评估集与 `RagEvalRunner`、`ai_retrieval_log` 观测端点。
