package com.freshman.agent.tool;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.freshman.agent.AgentTool;
import com.freshman.agent.ToolContext;
import com.freshman.agent.ToolResult;
import com.freshman.entity.*;
import com.freshman.mapper.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 工具：query_campus_data —— 校园结构化数据只读查询
 *
 * **安全是本工具的核心设计目标**（这是最容易被追问、也最容易做错的一个工具）：
 *  1. `entity` 是**枚举**，映射到固定的 Mapper 与输出字段白名单 ——
 *     **不存在自由 SQL 拼接路径**，也没有"让模型写 SQL"的口子
 *  2. `filters` 的键必须在白名单内；值一律走 `LambdaQueryWrapper.eq`（**参数化**）。
 *     注入尝试（如 `{"name":"' OR 1=1 --"}`）只会被当作普通字符串值去匹配 → 返回 0 行
 *  3. 行数上限 {@value #MAX_ROWS}、单字段截断 {@value #MAX_FIELD_CHARS} 字、
 *     整体序列化上限 {@value #MAX_TOTAL_CHARS} 字（防 token 爆炸）
 *  4. **输出字段白名单排除个人信息**：`club` 的 `president`/`contact` 绝不外泄；
 *     `guide_teacher` 整表不开放
 *  5. **全部只读**：只用 `selectList`，没有任何写操作
 *
 * 所属模块：DeepSeek 问答 / Agent
 */
@Component
public class QueryCampusDataTool implements AgentTool {

    private static final Logger log = LoggerFactory.getLogger(QueryCampusDataTool.class);

    static final int MAX_ROWS = 20;
    static final int MAX_FIELD_CHARS = 500;
    static final int MAX_TOTAL_CHARS = 4000;

    /** entity → 允许的 filters 键（白名单，键不在表内一律拒绝） */
    private static final Map<String, Set<String>> FILTER_WHITELIST = new LinkedHashMap<>();

    static {
        FILTER_WHITELIST.put("dormitory", Set.of("name", "buildingNo", "type", "roomType", "fee"));
        FILTER_WHITELIST.put("cafeteria", Set.of("name", "location", "floors"));
        FILTER_WHITELIST.put("club", Set.of("name", "category", "memberCount"));
        FILTER_WHITELIST.put("building", Set.of("name", "category", "address", "floors"));
        FILTER_WHITELIST.put("major", Set.of("name", "college", "degree", "duration"));
        FILTER_WHITELIST.put("registration_step", Set.of("stepNo", "title"));
    }

    private final DormitoryMapper dormitoryMapper;
    private final CafeteriaMapper cafeteriaMapper;
    private final ClubMapper clubMapper;
    private final BuildingMapper buildingMapper;
    private final MajorMapper majorMapper;
    private final RegistrationStepMapper stepMapper;

    public QueryCampusDataTool(DormitoryMapper dormitoryMapper,
                               CafeteriaMapper cafeteriaMapper,
                               ClubMapper clubMapper,
                               BuildingMapper buildingMapper,
                               MajorMapper majorMapper,
                               RegistrationStepMapper stepMapper) {
        this.dormitoryMapper = dormitoryMapper;
        this.cafeteriaMapper = cafeteriaMapper;
        this.clubMapper = clubMapper;
        this.buildingMapper = buildingMapper;
        this.majorMapper = majorMapper;
        this.stepMapper = stepMapper;
    }

    @Override
    public String name() {
        return "query_campus_data";
    }

    @Override
    public String description() {
        return "查询校园结构化数据（宿舍区、食堂、社团、校园建筑、专业、报到步骤）。"
                + "当用户问具体的事实型信息（费用多少、有没有空调、几个人一间、在哪、几点开门等）时使用。"
                + "entity 可选值：" + FILTER_WHITELIST.keySet();
    }

    @Override
    public Map<String, Object> parameters() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("entity", Map.of(
                "type", "string",
                "description", "要查询的数据类型",
                "enum", new ArrayList<>(FILTER_WHITELIST.keySet())));
        props.put("filters", Map.of(
                "type", "object",
                "description", "可选的等值过滤条件，键必须是该 entity 支持的字段："
                        + FILTER_WHITELIST));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", List.of("entity"));
        return schema;
    }

    @Override
    public ToolResult execute(Map<String, Object> args, ToolContext ctx) {
        Object rawEntity = args == null ? null : args.get("entity");
        String entity = rawEntity == null ? null : String.valueOf(rawEntity).trim();

        if (entity == null || !FILTER_WHITELIST.containsKey(entity)) {
            // 未知 entity：明确拒绝并列出合法取值（不回落到"随便查一张表"）
            return ToolResult.fail("不支持的 entity：" + entity + "，可选值为 " + FILTER_WHITELIST.keySet());
        }

        Map<String, Object> filters = asMap(args.get("filters"));
        Set<String> allowed = FILTER_WHITELIST.get(entity);
        for (String key : filters.keySet()) {
            if (!allowed.contains(key)) {
                return ToolResult.fail("filters 中不支持的字段：" + key + "，该 entity 仅支持 " + allowed);
            }
        }

        try {
            List<Map<String, Object>> rows = switch (entity) {
                case "dormitory" -> dormitory(filters);
                case "cafeteria" -> cafeteria(filters);
                case "club" -> club(filters);
                case "building" -> building(filters);
                case "major" -> major(filters);
                case "registration_step" -> registrationStep(filters);
                default -> List.of();
            };
            return render(entity, rows);
        } catch (Exception e) {
            log.warn("[Agent] query_campus_data 查询失败（entity={}）：{}", entity, e.getMessage());
            return ToolResult.fail("查询失败：" + e.getClass().getSimpleName());
        }
    }

    // ==================== 各 entity 的查询（只读 + 参数化 + 输出白名单） ====================

    private List<Map<String, Object>> dormitory(Map<String, Object> f) {
        List<Dormitory> list = dormitoryMapper.selectList(new LambdaQueryWrapper<Dormitory>()
                .eq(Dormitory::getStatus, 1)
                .eq(f.containsKey("name"), Dormitory::getName, val(f, "name"))
                .eq(f.containsKey("buildingNo"), Dormitory::getBuildingNo, val(f, "buildingNo"))
                .eq(f.containsKey("type"), Dormitory::getType, val(f, "type"))
                .eq(f.containsKey("roomType"), Dormitory::getRoomType, val(f, "roomType"))
                .eq(f.containsKey("fee"), Dormitory::getFee, val(f, "fee")));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Dormitory d : list) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", d.getName());
            m.put("buildingNo", d.getBuildingNo());
            m.put("type", d.getType());
            m.put("roomType", d.getRoomType());
            m.put("fee", d.getFee());
            m.put("facilities", cut(d.getFacilities()));
            m.put("description", cut(d.getDescription()));
            out.add(m);
        }
        return out;
    }

    private List<Map<String, Object>> cafeteria(Map<String, Object> f) {
        List<Cafeteria> list = cafeteriaMapper.selectList(new LambdaQueryWrapper<Cafeteria>()
                .eq(Cafeteria::getStatus, 1)
                .eq(f.containsKey("name"), Cafeteria::getName, val(f, "name"))
                .eq(f.containsKey("location"), Cafeteria::getLocation, val(f, "location"))
                .eq(f.containsKey("floors"), Cafeteria::getFloors, val(f, "floors")));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Cafeteria c : list) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", c.getName());
            m.put("location", c.getLocation());
            m.put("floors", c.getFloors());
            m.put("openingHours", c.getOpeningHours());
            m.put("specialties", cut(c.getSpecialties()));
            m.put("description", cut(c.getDescription()));
            out.add(m);
        }
        return out;
    }

    /** 注意：**刻意不输出 president / contact**（个人信息不外泄给第三方 LLM） */
    private List<Map<String, Object>> club(Map<String, Object> f) {
        List<Club> list = clubMapper.selectList(new LambdaQueryWrapper<Club>()
                .eq(Club::getStatus, 1)
                .eq(f.containsKey("name"), Club::getName, val(f, "name"))
                .eq(f.containsKey("category"), Club::getCategory, val(f, "category"))
                .eq(f.containsKey("memberCount"), Club::getMemberCount, val(f, "memberCount")));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Club c : list) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", c.getName());
            m.put("category", c.getCategory());
            m.put("memberCount", c.getMemberCount());
            m.put("recruitInfo", cut(c.getRecruitInfo()));
            m.put("activityTime", cut(c.getActivityTime()));
            m.put("location", cut(c.getLocation()));
            m.put("description", cut(c.getDescription()));
            out.add(m);
        }
        return out;
    }

    private List<Map<String, Object>> building(Map<String, Object> f) {
        List<Building> list = buildingMapper.selectList(new LambdaQueryWrapper<Building>()
                .eq(Building::getStatus, 1)
                .eq(f.containsKey("name"), Building::getName, val(f, "name"))
                .eq(f.containsKey("category"), Building::getCategory, val(f, "category"))
                .eq(f.containsKey("address"), Building::getAddress, val(f, "address"))
                .eq(f.containsKey("floors"), Building::getFloors, val(f, "floors")));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Building b : list) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", b.getName());
            m.put("category", b.getCategory());
            m.put("address", b.getAddress());
            m.put("floors", b.getFloors());
            m.put("openingHours", b.getOpeningHours());
            m.put("tags", cut(b.getTags()));
            m.put("description", cut(b.getDescription()));
            out.add(m);
        }
        return out;
    }

    private List<Map<String, Object>> major(Map<String, Object> f) {
        List<Major> list = majorMapper.selectList(new LambdaQueryWrapper<Major>()
                .eq(Major::getStatus, 1)
                .eq(f.containsKey("name"), Major::getName, val(f, "name"))
                .eq(f.containsKey("college"), Major::getCollege, val(f, "college"))
                .eq(f.containsKey("degree"), Major::getDegree, val(f, "degree"))
                .eq(f.containsKey("duration"), Major::getDuration, val(f, "duration")));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Major mj : list) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", mj.getName());
            m.put("college", mj.getCollege());
            m.put("degree", mj.getDegree());
            m.put("duration", mj.getDuration());
            m.put("description", cut(mj.getDescription()));
            m.put("courses", cut(mj.getCourses()));
            m.put("careerProspect", cut(mj.getCareerProspect()));
            out.add(m);
        }
        return out;
    }

    private List<Map<String, Object>> registrationStep(Map<String, Object> f) {
        List<RegistrationStep> list = stepMapper.selectList(new LambdaQueryWrapper<RegistrationStep>()
                .eq(RegistrationStep::getStatus, 1)
                .eq(f.containsKey("stepNo"), RegistrationStep::getStepNo, val(f, "stepNo"))
                .eq(f.containsKey("title"), RegistrationStep::getTitle, val(f, "title"))
                .orderByAsc(RegistrationStep::getStepNo));
        List<Map<String, Object>> out = new ArrayList<>();
        for (RegistrationStep s : list) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("stepNo", s.getStepNo());
            m.put("title", s.getTitle());
            m.put("location", s.getLocation());
            m.put("requiredMaterials", cut(s.getRequiredMaterials()));
            m.put("tips", cut(s.getTips()));
            m.put("description", cut(s.getDescription()));
            out.add(m);
        }
        return out;
    }

    // ==================== 渲染与工具方法 ====================

    private ToolResult render(String entity, List<Map<String, Object>> rows) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("entity", entity);
        meta.put("rowCount", rows.size());

        if (rows.isEmpty()) {
            return ToolResult.ok("未查询到匹配的「" + entity + "」数据。"
                    + "请如实告知用户没有查到，或改用其他条件/其他工具再试。", meta);
        }

        int capped = Math.min(rows.size(), MAX_ROWS);
        StringBuilder sb = new StringBuilder();
        sb.append("查询到 ").append(rows.size()).append(" 条「").append(entity).append("」数据");
        if (rows.size() > MAX_ROWS) {
            sb.append("，仅返回前 ").append(MAX_ROWS).append(" 条");
        }
        sb.append("：\n");
        List<Map<String, Object>> limited = rows.subList(0, capped);
        for (int i = 0; i < limited.size(); i++) {
            sb.append(i + 1).append(". ").append(limited.get(i)).append("\n");
            if (sb.length() > MAX_TOTAL_CHARS) {
                sb.append("…（结果过长已截断）\n");
                break;
            }
        }
        meta.put("rows", limited);
        return ToolResult.ok(sb.toString(), meta);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        if (o instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        return Map.of();
    }

    private static Object val(Map<String, Object> f, String key) {
        return f.get(key);
    }

    /** 单字段截断，防超长文本把上下文撑爆 */
    private static String cut(String s) {
        if (s == null) {
            return null;
        }
        return s.length() <= MAX_FIELD_CHARS ? s : s.substring(0, MAX_FIELD_CHARS) + "…";
    }
}
