package com.freshman.rag.source;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.freshman.entity.Building;
import com.freshman.mapper.BuildingMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 来源：campus_building（校园建筑，长文本）
 * 正文 = category + description + address + floors + openingHours + tags
 * 引用路径用详情页 /campus/{id}（该表也是唯一带经纬度的表）。
 *
 * 所属模块：AI 智能问答模块 / RAG
 */
@Component
public class CampusBuildingSource extends AbstractDocumentSource {

    private final BuildingMapper mapper;

    public CampusBuildingSource(BuildingMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public String sourceType() {
        return "campus_building";
    }

    @Override
    public String urlPathTemplate() {
        return "/campus/{id}";
    }

    @Override
    public boolean needsSplitting() {
        return true;
    }

    @Override
    public List<RawDocument> extract() {
        List<Building> rows = mapper.selectList(new LambdaQueryWrapper<Building>()
                .eq(Building::getStatus, 1));
        List<RawDocument> out = new ArrayList<>(rows.size());
        for (Building r : rows) {
            if (r.getId() == null) {
                continue;
            }
            out.add(new RawDocument(
                    r.getId(),
                    r.getName(),
                    join(r.getCategory(), r.getDescription(), r.getAddress(),
                            r.getFloors(), r.getOpeningHours(), r.getTags()),
                    null,
                    "校园导览"));
        }
        return out;
    }
}
