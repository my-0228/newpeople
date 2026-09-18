package com.freshman.rag;

import com.freshman.rag.dto.Chunk;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ChunkSplitter 单元测试（纯单元测试，不启动 Spring 上下文、不连数据库）。
 * 覆盖规格 §5.1 的六条规则与全部边界情况。
 */
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
        assertEquals(0, r.get(0).getIndex());
        assertEquals(0, r.get(0).getCharStart());
    }

    @Test
    void headingBoundariesSplitFirst() {
        // 注意：两段正文都必须长于 min-size，否则会被规则 ④ 合理地合并成一块
        String text = "## 一、报到流程\n新生报到需携带录取通知书原件、身份证原件及复印件等材料，到各学院迎新点核验后办理。"
                + "\n\n## 二、军训安排\n军训为期两周共14天，包含队列训练、内务整理与军事理论课，最后进行汇报表演。";
        List<Chunk> r = splitter.split(text, opts(400, 60, 30));
        assertEquals(2, r.size(), "应按标题切成 2 段，实际：" + r);
        assertTrue(r.get(0).getContent().contains("报到流程"));
        assertTrue(r.get(1).getContent().contains("军训安排"));
    }

    @Test
    void longParagraphSplitBySentenceAndNotExceedingSize() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            sb.append("这是第").append(i).append("个句子，用于测试切分。");
        }
        String text = sb.toString();
        List<Chunk> r = splitter.split(text, opts(100, 20, 30));
        assertTrue(r.size() > 1, "超长文本必须被切成多块");
        for (Chunk c : r) {
            assertTrue(c.getContent().length() <= 100 + 20,
                    "块长不应显著超过 size+overlap，实际 " + c.getContent().length());
        }
    }

    @Test
    void eachChunkEndsAtSentenceBoundary() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 30; i++) {
            sb.append("句子").append(i).append("。");
        }
        List<Chunk> r = splitter.split(sb.toString(), opts(60, 10, 10));
        for (Chunk c : r) {
            String s = c.getContent().trim();
            assertTrue(s.endsWith("。"),
                    "块应在句末标点处结束，实际结尾：" + s.substring(Math.max(0, s.length() - 5)));
        }
    }

    @Test
    void tinyFragmentMergedIntoNeighbour() {
        // 第一段 3 字（< min-size 30），第二、三段各约 40 字
        String text = "## A\n短短短。\n\n## B\n这是一段明显更长的正文内容，用来保证它自己不会被当成碎片合并掉。\n\n"
                + "## C\n这又是一段足够长的正文内容，长度超过最小块限制，因此不会被合并掉。";
        List<Chunk> r = splitter.split(text, opts(400, 60, 30));

        assertEquals(2, r.size(), "碎片应被并入相邻块，最终应为 2 块而非 3 块：" + r);
        for (Chunk c : r) {
            assertTrue(c.getContent().length() >= 30, "不应残留 < min-size 的碎片：" + c);
        }
        assertTrue(r.get(0).getContent().contains("短短短"), "被合并的碎片内容不应丢失");
    }

    @Test
    void headingOnlyWithoutBodyStillProducesChunk() {
        // 规格 §5.1 明确要求覆盖"仅标题无正文"的边界情况
        String text = "## 只有标题";
        List<Chunk> r = splitter.split(text, opts(400, 60, 30));
        assertEquals(1, r.size());
        assertEquals("## 只有标题", r.get(0).getContent());
        assertEquals(0, r.get(0).getCharStart());
    }

    @Test
    void unpunctuatedLongTextStillTerminates() {
        String text = "啊".repeat(1000);
        List<Chunk> r = splitter.split(text, opts(200, 0, 30));
        assertFalse(r.isEmpty(), "硬切兜底必须产生结果，而不是死循环");
        int total = r.stream().mapToInt(c -> c.getContent().length()).sum();
        assertEquals(1000, total, "硬切不应丢字");
    }

    @Test
    void charOffsetsPointBackToOriginalText() {
        String text = "## 一、甲\n甲乙丙丁。\n\n## 二、乙\n戊己庚辛。";
        List<Chunk> r = splitter.split(text, opts(400, 0, 10));
        assertFalse(r.isEmpty());
        int covered = 0;
        for (Chunk c : r) {
            assertTrue(c.getCharStart() >= 0 && c.getCharEnd() <= text.length(),
                    "偏移量必须在原文范围内：" + c);
            // 这条断言是"可失败"的：偏移量算错、或 content 与切片不一致，都会红
            assertEquals(c.getContent(), text.substring(c.getCharStart(), c.getCharEnd()),
                    "content 必须等于原文在该偏移区间的切片：" + c);
            covered += c.getContent().length();
        }
        assertTrue(covered > 0);
    }

    /** 规则③：overlap 窗口内没有句末标点时，宁可不重叠，也不从句中间开始 */
    @Test
    void overlapNeverStartsMidSentenceWhenNoBoundaryInWindow() {
        // 第一句 51 字、无内部标点；size=100/overlap=10 → overlap 窗口内无标点
        StringBuilder longSentence = new StringBuilder();
        for (int i = 0; i < 50; i++) {
            longSentence.append("字");
        }
        longSentence.append("。");
        String text = longSentence + "第二句也需要足够长才能触发第二块。" + "第三句同样需要足够长。";
        List<Chunk> r = splitter.split(text.toString(), opts(60, 10, 1));

        assertTrue(r.size() > 1, "应切成多块：" + r);
        for (int i = 1; i < r.size(); i++) {
            int start = r.get(i).getCharStart();
            // 每块的起点要么紧接上句句末，要么就是上一块的结尾（无重叠），不能落在句子中间
            assertTrue(start == 0 || "。！？；".indexOf(text.charAt(start - 1)) >= 0
                            || start == r.get(i - 1).getCharEnd(),
                    "第 " + i + " 块起点落在句子中间了：start=" + start
                            + " 前一个字符=" + text.charAt(start - 1));
        }
    }
}
