package com.freshman.rag.dto;

/**
 * 切分结果
 * charStart / charEnd 为在**原文中的偏移**，供引用溯源定位使用。
 *
 * 所属模块：AI 智能问答模块 / RAG
 */
public class Chunk {

    /** 文档内序号，从 0 开始 */
    private final int index;

    /** 分块正文（已 trim） */
    private final String content;

    /** 在原文中的起始偏移（含） */
    private final int charStart;

    /** 在原文中的结束偏移（不含） */
    private final int charEnd;

    public Chunk(int index, String content, int charStart, int charEnd) {
        this.index = index;
        this.content = content;
        this.charStart = charStart;
        this.charEnd = charEnd;
    }

    public int getIndex() { return index; }

    public String getContent() { return content; }

    public int getCharStart() { return charStart; }

    public int getCharEnd() { return charEnd; }

    @Override
    public String toString() {
        return "Chunk{index=" + index + ", len=" + (content == null ? 0 : content.length())
                + ", [" + charStart + "," + charEnd + ")}";
    }
}
