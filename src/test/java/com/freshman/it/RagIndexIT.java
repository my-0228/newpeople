package com.freshman.it;

import com.freshman.rag.KnowledgeIndexer;
import com.freshman.rag.VectorIndex;
import com.freshman.rag.dto.IndexReport;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RAG 数据层的端到端验证：**真实 MySQL + 真实百炼 Embedding API**。
 *
 * 运行（需先设置环境变量，否则所有向量化都会失败）：
 *   $env:DASHSCOPE_API_KEY = "<阿里 key>"
 *   mvn test -Dtest=RagIndexIT -Dtest.excludedGroups=eval
 *
 * 成本：10 张来源表共 173 行、切分后约 200 个 chunk、约 8~10 次批量请求，
 * 总成本在**分币级**。这是本计划唯一真实付费的一步，也是唯一能验证
 * "10 个来源实现是否真的各自产出了文档"和"向量是否真的落库"的地方。
 */
@Tag("it")
@SpringBootTest
class RagIndexIT {

    /** 规格 §5.7 的全部纳入来源（guide_teacher 刻意排除） */
    private static final List<String> EXPECTED_SOURCES = List.of(
            "ai_knowledge", "guide_faq", "guide_registration_step", "guide_major",
            "life_dormitory", "life_cafeteria", "life_club", "life_activity",
            "campus_building", "sys_news");

    @Autowired
    private KnowledgeIndexer indexer;

    @Autowired
    private VectorIndex vectorIndex;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private com.freshman.rag.RagProperties ragProperties;

    /**
     * 没有 API Key 时**跳过**本类，而不是让它跑失败。
     *
     * 为什么必须这样：本类的 4 个测试各触发一次全量重建；若 Key 缺失，
     * 每个 Embedding 批次都会重试 3 次并指数退避（1s+2s），
     * 173 个文档 → 约 10 个批次 × 3 次尝试 × 3 秒 ≈ 90 秒/测试，
     * 整类会跑到 10 分钟以上（实测曾导致 it 层 600 秒超时）。
     */
    @org.junit.jupiter.api.BeforeEach
    void requireApiKey() {
        String key = ragProperties.getEmbedding().getApiKey();
        org.junit.jupiter.api.Assumptions.assumeTrue(key != null && !key.isBlank(),
                "未设置 DASHSCOPE_API_KEY，跳过需要真实 Embedding 的端到端验证");
    }

    @Test
    void fullIndexProducesChunksAndVectors() {
        IndexReport report = indexer.rebuildAll(true);

        assertTrue(report.getDocumentCount() > 0, "应抽取到文档，实际：" + report);
        assertTrue(report.getChunkCount() >= report.getDocumentCount(),
                "chunk 数应 >= 文档数，实际：" + report);
        assertEquals(0, report.getFailedCount(), "首次全量重建不应有失败 chunk：" + report);

        Integer withVector = jdbc.queryForObject(
                "SELECT COUNT(*) FROM kb_chunk WHERE status = 1 AND embedding IS NOT NULL", Integer.class);
        assertNotNull(withVector);
        assertTrue(withVector > 0, "应有已向量化的 chunk");

        Integer wrongDim = jdbc.queryForObject(
                "SELECT COUNT(*) FROM kb_chunk WHERE status = 1 AND dim <> 1024", Integer.class);
        assertEquals(0, wrongDim, "向量维度应为 1024");

        assertEquals(withVector.intValue(), vectorIndex.size(),
                "内存索引条数应与库中已向量化条数一致（重建后应已刷新）");
    }

    /** 逐个来源断言：否则 10 个 DocumentSource 实现里有几个是空跑的根本发现不了 */
    @Test
    void everyExpectedSourceProducesAtLeastOneDocument() {
        indexer.rebuildAll(false);

        for (String source : EXPECTED_SOURCES) {
            Integer n = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM kb_document WHERE source_type = ?", Integer.class, source);
            assertNotNull(n);
            assertTrue(n > 0, "来源 " + source + " 应至少产出一条文档（检查该 DocumentSource 实现）");

            Integer chunks = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM kb_chunk c JOIN kb_document d ON c.document_id = d.id "
                            + "WHERE d.source_type = ?", Integer.class, source);
            assertNotNull(chunks);
            assertTrue(chunks > 0, "来源 " + source + " 应至少产出一条 chunk");
        }
    }

    /** Q/A 来源不切分：每个文档恰好 1 个 chunk */
    @Test
    void qaRowsAreStoredAsSingleChunk() {
        indexer.rebuildAll(false);

        List<Map<String, Object>> multi = jdbc.queryForList(
                "SELECT d.id, d.source_type, COUNT(*) AS n FROM kb_document d "
                        + "JOIN kb_chunk c ON c.document_id = d.id "
                        + "WHERE d.source_type IN ('ai_knowledge','guide_faq') "
                        + "GROUP BY d.id, d.source_type HAVING COUNT(*) > 1");
        assertTrue(multi.isEmpty(), "Q/A 对不应被切分成多块，实际：" + multi);
    }

    /** 引用可溯源：每个 chunk 都能通过 document 拿到 title；有模板的来源 url_path 已展开 */
    @Test
    void documentsCarryTitleAndUrlPath() {
        indexer.rebuildAll(false);

        Integer missingTitle = jdbc.queryForObject(
                "SELECT COUNT(*) FROM kb_document WHERE title IS NULL OR title = ''", Integer.class);
        assertEquals(0, missingTitle, "每个文档都应有标题（引用展示依赖它）");

        String newsPath = jdbc.queryForObject(
                "SELECT url_path FROM kb_document WHERE source_type = 'sys_news' LIMIT 1", String.class);
        assertNotNull(newsPath);
        assertTrue(newsPath.matches("/news/\\d+"), "模板应已展开为 /news/{id} 形式，实际：" + newsPath);

        String clubPath = jdbc.queryForObject(
                "SELECT url_path FROM kb_document WHERE source_type = 'life_club' LIMIT 1", String.class);
        assertEquals("/life/clubs", clubPath, "社团路由是复数");

        String aiPath = jdbc.queryForObject(
                "SELECT url_path FROM kb_document WHERE source_type = 'ai_knowledge' LIMIT 1", String.class);
        assertNull(aiPath, "ai_knowledge 无独立页面，url_path 应为 null");
    }

    /** 个人信息与检索辅助词都不能泄漏到正文/知识库 */
    @Test
    void privacyAndSearchTermsContractHolds() {
        indexer.rebuildAll(false);

        Integer excluded = jdbc.queryForObject(
                "SELECT COUNT(*) FROM kb_document WHERE source_type = 'guide_teacher'", Integer.class);
        assertEquals(0, excluded, "含 email 的 guide_teacher 不得进入知识库");

        // search_terms 绝不拼进正文（否则污染向量语义）
        List<Map<String, Object>> leaked = jdbc.queryForList(
                "SELECT id, search_terms FROM kb_chunk "
                        + "WHERE search_terms IS NOT NULL AND search_terms <> '' "
                        + "AND content LIKE CONCAT('%', search_terms, '%') LIMIT 5");
        assertTrue(leaked.isEmpty(), "search_terms 不应出现在正文中：" + leaked);

        // search_terms 不得超长（varchar(1000) 约束）
        Integer tooLong = jdbc.queryForObject(
                "SELECT COUNT(*) FROM kb_chunk WHERE CHAR_LENGTH(search_terms) > 1000", Integer.class);
        assertEquals(0, tooLong, "search_terms 应已按长度约束截断");
    }
}
