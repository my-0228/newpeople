package com.freshman.rag.source;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.freshman.entity.Cafeteria;
import com.freshman.mapper.CafeteriaMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 来源：life_cafeteria（食堂，长文本）
 * 正文 = location + floors + openingHours + description + specialties
 *
 * 所属模块：AI 智能问答模块 / RAG
 */
@Component
public class LifeCafeteriaSource extends AbstractDocumentSource {

    private final CafeteriaMapper mapper;

    public LifeCafeteriaSource(CafeteriaMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public String sourceType() {
        return "life_cafeteria";
    }

    @Override
    public String urlPathTemplate() {
        return "/life/cafeteria";
    }

    @Override
    public boolean needsSplitting() {
        return true;
    }

    @Override
    public List<RawDocument> extract() {
        List<Cafeteria> rows = mapper.selectList(new LambdaQueryWrapper<Cafeteria>()
                .eq(Cafeteria::getStatus, 1));
        List<RawDocument> out = new ArrayList<>(rows.size());
        for (Cafeteria r : rows) {
            if (r.getId() == null) {
                continue;
            }
            out.add(new RawDocument(
                    r.getId(),
                    r.getName(),
                    join(r.getLocation(), r.getFloors(), r.getOpeningHours(),
                            r.getDescription(), r.getSpecialties()),
                    null,
                    "校园生活"));
        }
        return out;
    }
}
