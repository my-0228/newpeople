package com.freshman.agent;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.freshman.entity.Building;
import com.freshman.mapper.BuildingMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 地点名 → 坐标解析器（供 plan_route 工具使用）
 *
 * 数据来源：`campus_building` —— 全库**唯一带经纬度**的表（已有 12 行真实坐标：
 * 学校正门、图书馆、第一教学楼…）。因此本解析器**今天就能对校内地点工作**。
 *
 * ⚠️ 关于演示地点（厚德学区/第一食堂等）：`campus_building` 里目前没有这些行，
 *    需要按 `docs/sql/2026-09-18_agent_schema.sql` 追加种子数据，**坐标必须用坐标拾取器核定**。
 *    本类**绝不猜测坐标** —— 解析不到就返回 empty，由工具转成 place_not_found 让模型向用户澄清。
 *
 * 匹配顺序：精确名 → 去后缀归一化 → `tags` 包含匹配（列名是 tags，不是 keywords）。
 *
 * 所属模块：DeepSeek 问答 / Agent
 */
@Component
public class PlaceResolver {

    private static final Logger log = LoggerFactory.getLogger(PlaceResolver.class);

    /** 归一化时剥离的后缀/前缀（学生口语与表内名称的常见差异） */
    private static final List<String> NOISE = List.of(
            "东北石油大学", "学校", "校区", "大楼", "楼", "馆", "区", "附近", "的");

    private final BuildingMapper buildingMapper;

    public PlaceResolver(BuildingMapper buildingMapper) {
        this.buildingMapper = buildingMapper;
    }

    /** 地点 */
    public record Place(Long buildingId, String name, double lat, double lng, String matchedBy) {}

    /**
     * 解析地点名。
     * 命中 0 → empty；命中多个 → 返回第一个（调用方可用 {@link #resolveCandidates} 做歧义澄清）。
     */
    public Optional<Place> resolve(String name) {
        List<Place> candidates = resolveCandidates(name);
        return candidates.isEmpty() ? Optional.empty() : Optional.of(candidates.get(0));
    }

    /** 解析候选（用于"歧义时让模型向用户澄清"） */
    public List<Place> resolveCandidates(String name) {
        List<Place> out = new ArrayList<>();
        if (name == null || name.isBlank()) {
            return out;
        }
        String raw = name.trim();
        List<Building> all = buildingMapper.selectList(new LambdaQueryWrapper<Building>()
                .eq(Building::getStatus, 1));

        // ① 精确名
        for (Building b : all) {
            if (raw.equals(b.getName())) {
                out.add(toPlace(b, "exact"));
            }
        }
        if (!out.isEmpty()) {
            return out;
        }

        // ② 去后缀归一化匹配（两侧都归一化后比较）
        String normalizedQuery = normalize(raw);
        for (Building b : all) {
            if (b.getName() != null && normalize(b.getName()).equals(normalizedQuery)) {
                out.add(toPlace(b, "normalized"));
            }
        }
        if (!out.isEmpty()) {
            return out;
        }

        // ③ tags 包含匹配（tags 是逗号分隔的关键词列）
        for (Building b : all) {
            if (b.getTags() != null && !b.getTags().isBlank()) {
                for (String tag : b.getTags().split("[,，]")) {
                    String t = tag.trim();
                    if (!t.isEmpty() && (raw.contains(t) || t.contains(normalizedQuery))) {
                        out.add(toPlace(b, "tags"));
                        break;
                    }
                }
            }
        }
        if (!out.isEmpty()) {
            log.debug("[Agent] 地点「{}」通过 tags 命中 {} 条", raw, out.size());
        }
        return out;
    }

    /** 归一化：小写 + 剥离噪音词 + 去空白 */
    static String normalize(String s) {
        String r = s.toLowerCase(Locale.ROOT).trim();
        for (String noise : NOISE) {
            r = r.replace(noise.toLowerCase(Locale.ROOT), "");
        }
        return r.replaceAll("\\s+", "");
    }

    private static Place toPlace(Building b, String matchedBy) {
        double lat = b.getLatitude() == null ? 0 : b.getLatitude();
        double lng = b.getLongitude() == null ? 0 : b.getLongitude();
        return new Place(b.getId(), b.getName(), lat, lng, matchedBy);
    }
}
