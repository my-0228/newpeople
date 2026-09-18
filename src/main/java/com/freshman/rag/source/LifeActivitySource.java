package com.freshman.rag.source;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.freshman.entity.Activity;
import com.freshman.mapper.ActivityMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 来源：life_activity（校园活动，长文本）
 * 正文 = description + category + location + organizer + startTime + endTime
 * （刻意不含 contact —— 联系方式不进 LLM 请求）
 *
 * 所属模块：AI 智能问答模块 / RAG
 */
@Component
public class LifeActivitySource extends AbstractDocumentSource {

    private final ActivityMapper mapper;

    public LifeActivitySource(ActivityMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public String sourceType() {
        return "life_activity";
    }

    @Override
    public String urlPathTemplate() {
        return "/life/activities";
    }

    @Override
    public boolean needsSplitting() {
        return true;
    }

    @Override
    public List<RawDocument> extract() {
        List<Activity> rows = mapper.selectList(new LambdaQueryWrapper<Activity>()
                .eq(Activity::getStatus, 1));
        List<RawDocument> out = new ArrayList<>(rows.size());
        for (Activity r : rows) {
            if (r.getId() == null) {
                continue;
            }
            out.add(new RawDocument(
                    r.getId(),
                    r.getTitle(),
                    join(r.getDescription(), r.getCategory(), r.getLocation(),
                            r.getOrganizer(), r.getStartTime(), r.getEndTime()),
                    null,
                    "校园生活"));
        }
        return out;
    }
}
