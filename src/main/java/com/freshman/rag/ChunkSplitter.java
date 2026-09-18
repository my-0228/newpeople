package com.freshman.rag;

import com.freshman.rag.dto.Chunk;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 结构感知切分器（RAG 语料处理的入口）
 *
 * 规则优先级（对应规格 §5.1）：
 *  ① 结构边界分段：Markdown 标题、中文序号、括号序号、阿拉伯序号、步骤N、连续空行
 *  ② 段内按中文句末标点（。！？；）定长切分，单段不超过 size 字
 *  ③ overlap：相邻块重叠若干字，起点**回退到句边界**，不从句子中间开始
 *  ④ 碎片合并：长度 < min-size 的块并入相邻块，避免碎片污染召回
 *  ⑤ 记录原文偏移，供引用溯源
 *  ⑥ 单句超长时按 size 硬切兜底，保证终止且不丢字
 *
 * 所属模块：AI 智能问答模块 / RAG
 * @author AI Module Team
 * @version 1.0
 */
@Component
public class ChunkSplitter {

    /** 结构边界：Markdown 标题 / 中文序号 / 括号序号 / 阿拉伯序号 / 步骤N */
    private static final Pattern LINE_STRUCT = Pattern.compile(
            "(?m)^(#{1,6}\\s|[一二三四五六七八九十]+、|（[一二三四五六七八九十\\d]+）|\\d+[.、]|步骤\\d+)");

    /** 连续空行（段间分隔） */
    private static final Pattern BLANK_LINE = Pattern.compile("\\n\\s*\\n");

    /** 中文句末标点 */
    private static final Set<Character> SENTENCE_END = Set.of('。', '！', '？', '；');

    /** 切分参数 */
    public record Options(int size, int overlap, int minSize) {}

    /**
     * 切分主流程。text 为 null 或纯空白时返回空列表。
     */
    public List<Chunk> split(String text, Options o) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        int size = Math.max(1, o.size());
        int overlap = Math.max(0, o.overlap());
        int minSize = Math.max(0, o.minSize());

        // ① 结构边界 → 若干 span
        List<int[]> spans = structuralSpans(text);

        // ② 段内定长切分
        List<int[]> pieces = new ArrayList<>();
        for (int[] span : spans) {
            pieces.addAll(splitSpan(text, span[0], span[1], size));
        }

        // ③ overlap（起点回退到句边界）
        if (overlap > 0 && pieces.size() > 1) {
            pieces = applyOverlap(text, pieces, overlap);
        }

        // ④ 碎片合并
        pieces = mergeTiny(text, pieces, minSize);

        // ⑤ 产出（trim 后记录精确偏移）
        List<Chunk> out = new ArrayList<>();
        for (int[] p : pieces) {
            int s = p[0];
            int e = p[1];
            while (s < e && Character.isWhitespace(text.charAt(s))) s++;
            while (e > s && Character.isWhitespace(text.charAt(e - 1))) e--;
            if (s >= e) continue;
            out.add(new Chunk(out.size(), text.substring(s, e), s, e));
        }
        return out;
    }

    // ==================== ① 结构分段 ====================

    /** 按结构标记与空行把原文切成互不重叠的 span（左闭右开） */
    private List<int[]> structuralSpans(String text) {
        TreeSet<Integer> cuts = new TreeSet<>();
        cuts.add(0);
        cuts.add(text.length());

        Matcher m = LINE_STRUCT.matcher(text);
        while (m.find()) {
            if (m.start() > 0) {
                cuts.add(m.start());
            }
        }
        Matcher b = BLANK_LINE.matcher(text);
        while (b.find()) {
            cuts.add(b.end());
        }

        List<int[]> spans = new ArrayList<>();
        Integer prev = null;
        for (Integer cut : cuts) {
            if (prev != null && cut > prev) {
                if (!text.substring(prev, cut).isBlank()) {
                    spans.add(new int[]{prev, cut});
                }
            }
            prev = cut;
        }
        if (spans.isEmpty()) {
            spans.add(new int[]{0, text.length()});
        }
        return spans;
    }

    // ==================== ② 段内定长（句边界优先，超长硬切） ====================

    private List<int[]> splitSpan(String text, int start, int end, int size) {
        List<int[]> out = new ArrayList<>();
        int segStart = start;
        int i = start;
        int lastSentenceEnd = -1;   // 最近一个句末标点的**后一位**

        while (i < end) {
            if (SENTENCE_END.contains(text.charAt(i))) {
                lastSentenceEnd = i + 1;
            }
            if (i - segStart + 1 >= size) {
                int cut = (lastSentenceEnd > segStart) ? lastSentenceEnd : i + 1;
                out.add(new int[]{segStart, cut});
                segStart = cut;
                lastSentenceEnd = -1;
                i = cut;
                continue;
            }
            i++;
        }
        if (segStart < end) {
            out.add(new int[]{segStart, end});
        }
        return out;
    }

    // ==================== ③ overlap ====================

    private List<int[]> applyOverlap(String text, List<int[]> pieces, int overlap) {
        List<int[]> out = new ArrayList<>(pieces.size());
        for (int i = 0; i < pieces.size(); i++) {
            int[] p = pieces.get(i);
            int s = p[0];
            if (i > 0) {
                int prevStart = pieces.get(i - 1)[0];
                int candidate = Math.max(prevStart, s - overlap);
                // 规则③：起点必须落在句边界之后。若窗口内没有任何句末标点，
                // **宁可不重叠**也不能从句中间开始（规格明确"禁止从句中间截断"）。
                int snapped = s;
                for (int k = candidate; k < s; k++) {
                    if (SENTENCE_END.contains(text.charAt(k))) {
                        snapped = k + 1;
                    }
                }
                s = snapped;
            }
            out.add(new int[]{s, p[1]});
        }
        return out;
    }

    // ==================== ④ 碎片合并 ====================

    private List<int[]> mergeTiny(String text, List<int[]> pieces, int minSize) {
        List<int[]> work = new ArrayList<>(pieces);
        boolean changed = true;
        while (changed && work.size() > 1) {
            changed = false;
            for (int i = 0; i < work.size(); i++) {
                int[] p = work.get(i);
                if (trimmedLength(text, p[0], p[1]) >= minSize) continue;
                if (i > 0) {
                    // 并入前一块
                    work.set(i - 1, new int[]{work.get(i - 1)[0], p[1]});
                    work.remove(i);
                } else {
                    // 首块 → 并入后一块
                    work.set(i + 1, new int[]{p[0], work.get(i + 1)[1]});
                    work.remove(i);
                }
                changed = true;
                break;
            }
        }
        return work;
    }

    private int trimmedLength(String text, int s, int e) {
        while (s < e && Character.isWhitespace(text.charAt(s))) s++;
        while (e > s && Character.isWhitespace(text.charAt(e - 1))) e--;
        return e - s;
    }
}
