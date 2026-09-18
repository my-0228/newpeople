package com.freshman.rag.source;

import java.util.List;

/**
 * 业务文档来源：**一个实现对应一张业务表**
 *
 * 索引器对每个实现调用 {@link #extract()} 拿到"文档级"数据，
 * 再统一清洗、切分、向量化、落库。这样新增来源只需加一个实现，不用改索引器。
 *
 * 所属模块：AI 智能问答模块 / RAG
 */
public interface DocumentSource {

    /** 来源类型标识，必须唯一，如 ai_knowledge / life_dormitory */
    String sourceType();

    /**
     * 引用跳转路径模板；返回 null 表示该来源没有独立页面（前端展开原文）。
     * 模板中的 <code>{id}</code> 会被替换为该行的 sourceId。
     */
    String urlPathTemplate();

    /** 是否需要按 §5.1 规则切分（Q/A 对返回 false，长文本返回 true） */
    boolean needsSplitting();

    /** 抽取全部原始文档 */
    List<RawDocument> extract();

    /** 计算某条文档的引用路径（模板为 null 时返回 null） */
    default String urlPathFor(RawDocument doc) {
        String t = urlPathTemplate();
        if (t == null) {
            return null;
        }
        return t.replace("{id}", String.valueOf(doc.sourceId()));
    }

    /**
     * 文档级原始数据
     *
     * @param sourceId    来源表主键，**绝不允许为 null**（唯一键 uk_source 不阻止 NULL 重复行，
     *                    因此"一来源一文档"依赖代码保证）
     * @param title       标题，用于引用展示
     * @param body        参与切分与向量化的正文
     * @param searchTerms 关键词/同义词（供关键词检索路径，**不参与向量化**），可为 null
     * @param category    分类
     */
    record RawDocument(Long sourceId, String title, String body, String searchTerms, String category) {}
}
