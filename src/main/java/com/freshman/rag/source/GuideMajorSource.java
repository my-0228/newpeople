package com.freshman.rag.source;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.freshman.entity.Major;
import com.freshman.mapper.MajorMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 来源：guide_major（专业介绍，长文本）
 * 正文 = college + degree + duration + description + courses + careerProspect + features
 *
 * 所属模块：AI 智能问答模块 / RAG
 */
@Component
public class GuideMajorSource extends AbstractDocumentSource {

    private final MajorMapper mapper;

    public GuideMajorSource(MajorMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public String sourceType() {
        return "guide_major";
    }

    @Override
    public String urlPathTemplate() {
        return "/guide/majors";
    }

    @Override
    public boolean needsSplitting() {
        return true;
    }

    @Override
    public List<RawDocument> extract() {
        List<Major> rows = mapper.selectList(new LambdaQueryWrapper<Major>()
                .eq(Major::getStatus, 1));
        List<RawDocument> out = new ArrayList<>(rows.size());
        for (Major r : rows) {
            if (r.getId() == null) {
                continue;
            }
            out.add(new RawDocument(
                    r.getId(),
                    r.getName(),
                    join(r.getCollege(), r.getDegree(), r.getDuration(),
                            r.getDescription(), r.getCourses(), r.getCareerProspect(), r.getFeatures()),
                    null,
                    "专业介绍"));
        }
        return out;
    }
}
