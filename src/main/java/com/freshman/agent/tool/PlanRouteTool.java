package com.freshman.agent.tool;

import com.freshman.agent.AgentTool;
import com.freshman.agent.PlaceResolver;
import com.freshman.agent.ToolContext;
import com.freshman.agent.ToolResult;
import com.freshman.service.BaiduNavigationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 工具：plan_route —— 校园内步行路线规划
 *
 * 三段式：`PlaceResolver` 把中文地点名解析为坐标 → 复用 `BaiduNavigationService`
 * （含 WGS-84↔GCJ-02 换算与三级容灾）→ 输出距离与耗时。
 *
 * **坐标口径**：只使用 `campus_building` 里**已核定的真实坐标**；
 * 解析不到地点时**如实反馈 `place_not_found` 让模型向用户澄清，绝不猜测坐标**
 * （猜测会给出看似合理实则错误的结果，比拒答更糟）。
 *
 * 所属模块：DeepSeek 问答 / Agent
 */
@Component
public class PlanRouteTool implements AgentTool {

    private static final Logger log = LoggerFactory.getLogger(PlanRouteTool.class);

    private static final String HINT =
            "可尝试使用校园内已有的地点名（如「学校正门」「图书馆」「第一教学楼」等），"
                    + "或请用户换一个说法。";

    private final PlaceResolver placeResolver;
    private final BaiduNavigationService navigationService;

    public PlanRouteTool(PlaceResolver placeResolver, BaiduNavigationService navigationService) {
        this.placeResolver = placeResolver;
        this.navigationService = navigationService;
    }

    @Override
    public String name() {
        return "plan_route";
    }

    @Override
    public String description() {
        return "规划校园内两个地点之间的步行路线，返回距离与预计耗时。"
                + "当用户问「从A到B怎么走/多远/要多久」时使用。需要提供起点与终点的中文地点名。";
    }

    @Override
    public Map<String, Object> parameters() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("from", Map.of("type", "string", "description", "起点地点名，如「学校正门」"));
        props.put("to", Map.of("type", "string", "description", "终点地点名，如「图书馆」"));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", List.of("from", "to"));
        return schema;
    }

    @Override
    public ToolResult execute(Map<String, Object> args, ToolContext ctx) {
        String fromName = str(args, "from");
        String toName = str(args, "to");
        if (fromName == null || toName == null) {
            return ToolResult.fail("缺少参数 from 或 to");
        }

        Optional<PlaceResolver.Place> from = placeResolver.resolve(fromName);
        if (from.isEmpty()) {
            return notFound("起点", fromName);
        }
        Optional<PlaceResolver.Place> to = placeResolver.resolve(toName);
        if (to.isEmpty()) {
            return notFound("终点", toName);
        }

        PlaceResolver.Place f = from.get();
        PlaceResolver.Place t = to.get();
        if (f.lat() == 0 && f.lng() == 0 || t.lat() == 0 && t.lng() == 0) {
            // 表里该行的经纬度为空：不能拿 (0,0) 当坐标去算路线
            return ToolResult.fail("地点「" + (f.lat() == 0 ? f.name() : t.name())
                    + "」缺少经纬度数据，无法规划路线。" + HINT);
        }

        try {
            BaiduNavigationService.RouteResult route = navigationService.getWalkingRoute(
                    f.lat(), f.lng(), f.name(), t.lat(), t.lng(), t.name());
            if (route == null) {
                return ToolResult.fail("路线服务未能返回结果（可能是地点名不被地图服务识别），" + HINT);
            }
            long minutes = Math.max(1, Math.round(route.durationSeconds / 60.0));
            String content = String.format("从「%s」步行到「%s」：约 %.0f 米，步行约 %d 分钟。",
                    f.name(), t.name(), route.distanceMeters, minutes);

            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("distanceMeters", Math.round(route.distanceMeters));
            meta.put("durationMinutes", minutes);
            meta.put("from", f.name());
            meta.put("to", t.name());
            meta.put("matchedBy", f.matchedBy() + "→" + t.matchedBy());
            return ToolResult.ok(content, meta);
        } catch (Exception e) {
            log.warn("[Agent] plan_route 调用导航服务失败：{}", e.getMessage());
            return ToolResult.fail("路线规划失败：" + e.getClass().getSimpleName());
        }
    }

    /** 地点未收录：如实告知 + 让模型澄清（**不猜坐标**） */
    private ToolResult notFound(String which, String name) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("error", "place_not_found");
        meta.put("missing", name);
        return ToolResult.fail(which + "「" + name + "」不在校园地点库中，无法规划路线。"
                + "请如实告知用户你没有该地点的位置信息，并请其确认地点名称。" + HINT, meta);
    }

    private static String str(Map<String, Object> args, String key) {
        Object v = args == null ? null : args.get(key);
        if (v == null) {
            return null;
        }
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? null : s;
    }
}
