package com.freshman.rag.source;

/**
 * 来源实现基类：提供一个把非空字段拼成正文的小工具，避免 10 个实现重复拼接逻辑。
 *
 * 所属模块：AI 智能问答模块 / RAG
 */
public abstract class AbstractDocumentSource implements DocumentSource {

    /** 按顺序拼接非空字段，用换行分隔；全为空时返回空串 */
    protected static String join(Object... parts) {
        StringBuilder sb = new StringBuilder();
        for (Object p : parts) {
            if (p == null) {
                continue;
            }
            String s = String.valueOf(p).trim();
            if (s.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(s);
        }
        return sb.toString();
    }

    /** 用逗号拼接非空字段（用于 keywords / synonyms 这类检索辅助词） */
    protected static String joinCsv(Object... parts) {
        StringBuilder sb = new StringBuilder();
        for (Object p : parts) {
            if (p == null) {
                continue;
            }
            String s = String.valueOf(p).trim();
            if (s.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(s);
        }
        return sb.toString();
    }
}
