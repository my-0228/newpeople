package com.freshman.rag.source;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.freshman.entity.News;
import com.freshman.mapper.NewsMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 来源：sys_news（新闻公告，长文本）
 * 正文 = summary + content；引用路径 /news/{id}
 *
 * 所属模块：AI 智能问答模块 / RAG
 */
@Component
public class SysNewsSource extends AbstractDocumentSource {

    private final NewsMapper mapper;

    public SysNewsSource(NewsMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public String sourceType() {
        return "sys_news";
    }

    @Override
    public String urlPathTemplate() {
        return "/news/{id}";
    }

    @Override
    public boolean needsSplitting() {
        return true;
    }

    @Override
    public List<RawDocument> extract() {
        List<News> rows = mapper.selectList(new LambdaQueryWrapper<News>()
                .eq(News::getStatus, 1));
        List<RawDocument> out = new ArrayList<>(rows.size());
        for (News r : rows) {
            if (r.getId() == null) {
                continue;
            }
            out.add(new RawDocument(
                    r.getId(),
                    r.getTitle(),
                    join(r.getSummary(), r.getContent()),
                    null,
                    "新闻公告"));
        }
        return out;
    }
}
