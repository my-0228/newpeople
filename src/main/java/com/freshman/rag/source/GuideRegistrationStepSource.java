package com.freshman.rag.source;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.freshman.entity.RegistrationStep;
import com.freshman.mapper.RegistrationStepMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 来源：guide_registration_step（报到流程步骤，长文本）
 * 正文 = title + description + location + requiredMaterials + tips；需要按步骤边界切分。
 *
 * 所属模块：AI 智能问答模块 / RAG
 */
@Component
public class GuideRegistrationStepSource extends AbstractDocumentSource {

    private final RegistrationStepMapper mapper;

    public GuideRegistrationStepSource(RegistrationStepMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public String sourceType() {
        return "guide_registration_step";
    }

    @Override
    public String urlPathTemplate() {
        return "/guide/registration";
    }

    @Override
    public boolean needsSplitting() {
        return true;
    }

    @Override
    public List<RawDocument> extract() {
        List<RegistrationStep> rows = mapper.selectList(new LambdaQueryWrapper<RegistrationStep>()
                .eq(RegistrationStep::getStatus, 1)
                .orderByAsc(RegistrationStep::getStepNo));
        List<RawDocument> out = new ArrayList<>(rows.size());
        for (RegistrationStep r : rows) {
            if (r.getId() == null) {
                continue;
            }
            out.add(new RawDocument(
                    r.getId(),
                    r.getTitle(),
                    join(r.getTitle(), r.getDescription(), r.getLocation(), r.getRequiredMaterials(), r.getTips()),
                    null,
                    "报到流程"));
        }
        return out;
    }
}
