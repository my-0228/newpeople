package com.freshman.rag.source;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.freshman.entity.Faq;
import com.freshman.mapper.FaqMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 来源：guide_faq（迎新指南·常见问题，一问一答）
 * 与 ai_knowledge 同样的策略：不切分，一行一 chunk。
 *
 * 所属模块：AI 智能问答模块 / RAG
 */
@Component
public class GuideFaqSource extends AbstractDocumentSource {

    private final FaqMapper mapper;

    public GuideFaqSource(FaqMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public String sourceType() {
        return "guide_faq";
    }

    @Override
    public String urlPathTemplate() {
        return "/guide/faq";
    }

    @Override
    public boolean needsSplitting() {
        return false;
    }

    @Override
    public List<RawDocument> extract() {
        List<Faq> rows = mapper.selectList(new LambdaQueryWrapper<Faq>()
                .eq(Faq::getStatus, 1));
        List<RawDocument> out = new ArrayList<>(rows.size());
        for (Faq r : rows) {
            if (r.getId() == null) {
                continue;
            }
            out.add(new RawDocument(
                    r.getId(),
                    r.getQuestion(),
                    join(r.getQuestion(), r.getAnswer()),
                    null,
                    r.getCategory()));
        }
        return out;
    }
}
