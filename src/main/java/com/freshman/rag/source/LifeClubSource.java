package com.freshman.rag.source;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.freshman.entity.Club;
import com.freshman.mapper.ClubMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 来源：life_club（社团，长文本）
 * 正文 = category + description + memberCount + recruitInfo + activityTime + location
 *
 * ⚠️ **必须排除 `president` 与 `contact`** —— 社团负责人姓名与联系方式属个人信息，
 * 不应进入第三方 LLM 请求（规格 §5.7 的隐私约束）。
 *
 * 所属模块：AI 智能问答模块 / RAG
 */
@Component
public class LifeClubSource extends AbstractDocumentSource {

    private final ClubMapper mapper;

    public LifeClubSource(ClubMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public String sourceType() {
        return "life_club";
    }

    @Override
    public String urlPathTemplate() {
        return "/life/clubs";   // 注意是复数（与 LifeController 的实际路由一致）
    }

    @Override
    public boolean needsSplitting() {
        return true;
    }

    @Override
    public List<RawDocument> extract() {
        List<Club> rows = mapper.selectList(new LambdaQueryWrapper<Club>()
                .eq(Club::getStatus, 1));
        List<RawDocument> out = new ArrayList<>(rows.size());
        for (Club r : rows) {
            if (r.getId() == null) {
                continue;
            }
            out.add(new RawDocument(
                    r.getId(),
                    r.getName(),
                    // 刻意不含 president / contact
                    join(r.getCategory(), r.getDescription(), r.getMemberCount(),
                            r.getRecruitInfo(), r.getActivityTime(), r.getLocation()),
                    null,
                    "社团"));
        }
        return out;
    }
}
