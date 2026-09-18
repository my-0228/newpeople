package com.freshman.rag.source;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.freshman.entity.Dormitory;
import com.freshman.mapper.DormitoryMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 来源：life_dormitory（宿舍区，长文本）
 * 正文 = buildingNo + type + roomType + facilities + description + fee
 *
 * 所属模块：AI 智能问答模块 / RAG
 */
@Component
public class LifeDormitorySource extends AbstractDocumentSource {

    private final DormitoryMapper mapper;

    public LifeDormitorySource(DormitoryMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public String sourceType() {
        return "life_dormitory";
    }

    @Override
    public String urlPathTemplate() {
        return "/life/dormitory";
    }

    @Override
    public boolean needsSplitting() {
        return true;
    }

    @Override
    public List<RawDocument> extract() {
        List<Dormitory> rows = mapper.selectList(new LambdaQueryWrapper<Dormitory>()
                .eq(Dormitory::getStatus, 1));
        List<RawDocument> out = new ArrayList<>(rows.size());
        for (Dormitory r : rows) {
            if (r.getId() == null) {
                continue;
            }
            out.add(new RawDocument(
                    r.getId(),
                    r.getName(),
                    join(r.getBuildingNo(), r.getType(), r.getRoomType(),
                            r.getFacilities(), r.getDescription(), r.getFee()),
                    null,
                    "宿舍"));
        }
        return out;
    }
}
