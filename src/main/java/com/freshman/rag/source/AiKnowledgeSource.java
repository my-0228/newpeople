package com.freshman.rag.source;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.freshman.entity.AiKnowledge;
import com.freshman.mapper.AiKnowledgeMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 来源：ai_knowledge（AI 知识库，一问一答）
 *
 * 正文 = question + "\n" + answer（问题提供语义入口，答案提供细节匹配）；
 * keywords / synonyms 只作为 search_terms，**不拼进正文**（避免污染向量语义）。
 * 一行天然是一个完整语义单元，**不切分**。
 *
 * 所属模块：AI 智能问答模块 / RAG
 */
@Component
public class AiKnowledgeSource extends AbstractDocumentSource {

    private final AiKnowledgeMapper mapper;

    public AiKnowledgeSource(AiKnowledgeMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public String sourceType() {
        return "ai_knowledge";
    }

    @Override
    public String urlPathTemplate() {
        return null;   // 无独立页面，前端展开原文
    }

    @Override
    public boolean needsSplitting() {
        return false;
    }

    @Override
    public List<RawDocument> extract() {
        List<AiKnowledge> rows = mapper.selectList(new LambdaQueryWrapper<AiKnowledge>()
                .eq(AiKnowledge::getStatus, 1));
        List<RawDocument> out = new ArrayList<>(rows.size());
        for (AiKnowledge r : rows) {
            if (r.getId() == null) {
                continue;
            }
            out.add(new RawDocument(
                    r.getId(),
                    r.getQuestion(),
                    join(r.getQuestion(), r.getAnswer()),
                    joinCsv(r.getKeywords(), r.getSynonyms()),
                    r.getCategory()));
        }
        return out;
    }
}
