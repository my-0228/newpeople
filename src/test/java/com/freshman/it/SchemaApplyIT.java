package com.freshman.it;

import com.freshman.entity.AiRetrievalLog;
import com.freshman.entity.KbChunk;
import com.freshman.entity.KbDocument;
import com.freshman.mapper.AiRetrievalLogMapper;
import com.freshman.mapper.KbChunkMapper;
import com.freshman.mapper.KbDocumentMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.StreamUtils;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 执行 RAG schema 脚本，验证幂等性、表结构，以及**实体↔列映射的真实往返**。
 * 需要真实 MySQL。
 *
 * 为什么需要三个测试而不是一个：
 *  - 只断言 information_schema 的话，列类型/长度改动、甚至实体字段名与列名不匹配都会绿
 *    （近乎同义反复）；
 *  - 因此补一个 insert→select 往返，用真实的 Mapper 走一遍 MyBatis-Plus 的
 *    驼峰↔下划线映射与类型转换（bigint/tinyint/decimal/mediumtext）。
 */
@Tag("it")
@SpringBootTest
class SchemaApplyIT {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private KbDocumentMapper kbDocumentMapper;

    @Autowired
    private KbChunkMapper kbChunkMapper;

    @Autowired
    private AiRetrievalLogMapper aiRetrievalLogMapper;

    /** 项目原有的业务表：脚本必须一张都不动（回归护栏） */
    private static final List<String> PRE_EXISTING_TABLES = List.of(
            "ai_chat_history", "ai_knowledge", "campus_building", "forum_comment", "forum_post",
            "guide_faq", "guide_major", "guide_registration_step", "guide_teacher",
            "life_activity", "life_cafeteria", "life_club", "life_dormitory",
            "sys_news", "sys_role", "sys_user", "sys_user_role");

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

        // 向量列必须是 mediumtext（MySQL 5.6 无 JSON 列类型，向量只能以文本存）
        String embeddingType = jdbc.queryForObject(
                "SELECT column_type FROM information_schema.columns " +
                "WHERE table_schema = DATABASE() AND table_name = 'kb_chunk' AND column_name = 'embedding'",
                String.class);
        assertEquals("mediumtext", embeddingType == null ? null : embeddingType.toLowerCase(),
                "向量列类型应为 mediumtext");
    }

    /** 脚本是"只增不改"：原有 17 张业务表必须一张不少 */
    @Test
    void existingTablesAreNotDroppedOrRenamed() throws Exception {
        applyScript();
        for (String table : PRE_EXISTING_TABLES) {
            Integer c = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.tables " +
                    "WHERE table_schema = DATABASE() AND table_name = ?", Integer.class, table);
            assertEquals(1, c, "原有业务表不应被脚本影响：" + table);
        }
    }

    /**
     * 实体 ↔ 列的真实往返：走 MyBatis-Plus 的驼峰↔下划线映射与类型转换。
     * 这是唯一能抓住"实体字段名写错/类型写错"的断言。
     */
    @Test
    void entityRoundTripMatchesColumns() {
        String hash = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

        // ---------- KbDocument ----------
        KbDocument doc = new KbDocument();
        doc.setSourceType("it_probe");
        doc.setSourceId(999_999_001L);
        doc.setTitle("往返测试文档");
        doc.setCategory("测试");
        doc.setUrlPath("/it/probe");
        doc.setContentHash(hash);
        doc.setChunkCount(1);
        doc.setStatus(1);
        kbDocumentMapper.insert(doc);
        assertNotNull(doc.getId(), "自增主键应被回填");

        KbDocument loadedDoc = kbDocumentMapper.selectById(doc.getId());
        assertNotNull(loadedDoc);
        assertEquals("it_probe", loadedDoc.getSourceType(), "sourceType ↔ source_type 映射");
        assertEquals(999_999_001L, loadedDoc.getSourceId(), "sourceId ↔ source_id 映射");
        assertEquals("往返测试文档", loadedDoc.getTitle());
        assertEquals("/it/probe", loadedDoc.getUrlPath(), "urlPath ↔ url_path 映射");
        assertEquals(Integer.valueOf(1), loadedDoc.getChunkCount(), "chunkCount ↔ chunk_count 映射");
        assertNotNull(loadedDoc.getCreateTime(), "create_time 应由 DB 默认值填充");

        // ---------- KbChunk（含 mediumtext 向量列的往返）----------
        String embeddingText = "[-0.022868,0.042135,0.0]";
        KbChunk chunk = new KbChunk();
        chunk.setDocumentId(doc.getId());
        chunk.setChunkIndex(0);
        chunk.setContent("往返测试正文");
        chunk.setSearchTerms("往返,测试");
        chunk.setCharStart(0);
        chunk.setCharEnd(6);
        chunk.setContentHash(hash);
        chunk.setEmbedding(embeddingText);
        chunk.setEmbeddingModel("text-embedding-v3");
        chunk.setDim(3);
        chunk.setStatus(1);
        kbChunkMapper.insert(chunk);
        assertNotNull(chunk.getId());

        KbChunk loadedChunk = kbChunkMapper.selectById(chunk.getId());
        assertNotNull(loadedChunk);
        assertEquals(doc.getId(), loadedChunk.getDocumentId());
        assertEquals(Integer.valueOf(0), loadedChunk.getChunkIndex(), "chunkIndex ↔ chunk_index 映射");
        assertEquals(embeddingText, loadedChunk.getEmbedding(), "mediumtext 向量文本应原样往返");
        assertEquals("往返,测试", loadedChunk.getSearchTerms(), "searchTerms ↔ search_terms 映射");
        assertEquals(Integer.valueOf(3), loadedChunk.getDim());

        // ---------- AiRetrievalLog（decimal + tinyint → BigDecimal/Integer）----------
        AiRetrievalLog log = new AiRetrievalLog();
        log.setSessionId("it-session");
        log.setQuestion("往返测试问题");
        log.setVectorHits(3);
        log.setKeywordHits(2);
        log.setGateMode("vector");
        log.setTopScore(new BigDecimal("0.6231"));
        log.setFinalChunkIds(String.valueOf(chunk.getId()));
        log.setIsUnknown(0);
        log.setDegraded(0);
        log.setEmbeddingMs(12);
        log.setRetrievalMs(3);
        // generateMs 故意留 null —— 拒答路径的语义就是 NULL
        aiRetrievalLogMapper.insert(log);
        assertNotNull(log.getId());

        AiRetrievalLog loadedLog = aiRetrievalLogMapper.selectById(log.getId());
        assertNotNull(loadedLog);
        assertEquals("vector", loadedLog.getGateMode(), "gateMode ↔ gate_mode 映射");
        assertEquals(0, new BigDecimal("0.6231").compareTo(loadedLog.getTopScore()),
                "topScore 应为 decimal(6,4) 且精度不丢");
        assertEquals(Integer.valueOf(0), loadedLog.getIsUnknown(), "isUnknown ↔ is_unknown 映射（Integer 而非 Boolean）");
        assertNull(loadedLog.getGenerateMs(), "未调用 LLM 时 generate_ms 应为 NULL");

        // ---------- 清理，保持库状态干净 ----------
        aiRetrievalLogMapper.deleteById(log.getId());
        kbChunkMapper.deleteById(chunk.getId());
        kbDocumentMapper.deleteById(doc.getId());
        assertNull(kbDocumentMapper.selectById(doc.getId()), "清理后不应残留");
    }
}
