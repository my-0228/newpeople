package com.freshman.agent.tool;

import com.freshman.agent.ToolContext;
import com.freshman.agent.ToolResult;
import com.freshman.entity.Club;
import com.freshman.entity.Dormitory;
import com.freshman.mapper.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * QueryCampusDataTool 单元测试（Mock 6 个 Mapper，不连库）。
 * 重点：**枚举白名单、filters 白名单、PII 排除、行数上限与截断** —— 这是 Agent 里
 * 安全要求最高的一个工具。
 */
class QueryCampusDataToolTest {

    private DormitoryMapper dormitoryMapper;
    private CafeteriaMapper cafeteriaMapper;
    private ClubMapper clubMapper;
    private BuildingMapper buildingMapper;
    private MajorMapper majorMapper;
    private RegistrationStepMapper stepMapper;
    private QueryCampusDataTool tool;

    private final ToolContext ctx = new ToolContext("s", null, "127.0.0.1");

    @BeforeEach
    void setUp() {
        dormitoryMapper = mock(DormitoryMapper.class);
        cafeteriaMapper = mock(CafeteriaMapper.class);
        clubMapper = mock(ClubMapper.class);
        buildingMapper = mock(BuildingMapper.class);
        majorMapper = mock(MajorMapper.class);
        stepMapper = mock(RegistrationStepMapper.class);
        tool = new QueryCampusDataTool(dormitoryMapper, cafeteriaMapper, clubMapper,
                buildingMapper, majorMapper, stepMapper);

        when(dormitoryMapper.selectList(any())).thenReturn(List.of());
        when(cafeteriaMapper.selectList(any())).thenReturn(List.of());
        when(clubMapper.selectList(any())).thenReturn(List.of());
        when(buildingMapper.selectList(any())).thenReturn(List.of());
        when(majorMapper.selectList(any())).thenReturn(List.of());
        when(stepMapper.selectList(any())).thenReturn(List.of());
    }

    private static Dormitory dormitory(String name, String roomType, BigDecimal fee, String facilities) {
        Dormitory d = new Dormitory();
        d.setName(name);
        d.setBuildingNo("H1");
        d.setType("四人间");
        d.setRoomType(roomType);
        d.setFee(fee);
        d.setFacilities(facilities);
        d.setDescription("描述");
        d.setStatus(1);
        return d;
    }

    @Test
    void nameAndSchemaExposeEntityEnum() {
        assertEquals("query_campus_data", tool.name());
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) tool.parameters().get("properties");
        @SuppressWarnings("unchecked")
        Map<String, Object> entity = (Map<String, Object>) props.get("entity");
        List<?> allowed = (List<?>) entity.get("enum");
        assertTrue(allowed.contains("dormitory"));
        assertTrue(allowed.contains("registration_step"));
        assertFalse(allowed.contains("teacher"), "guide_teacher 不开放（含个人信息）");
    }

    @Test
    void unknownEntityIsRejectedWithAllowedValues() {
        ToolResult r = tool.execute(Map.of("entity", "sys_user"), ctx);

        assertFalse(r.success(), "未知 entity 必须拒绝，不能回落去查别的表");
        assertTrue(r.content().contains("不支持的 entity"));
        assertTrue(r.content().contains("dormitory"), "应列出合法取值：" + r.content());
        verify(dormitoryMapper, never()).selectList(any());
    }

    @Test
    void missingEntityIsRejected() {
        assertFalse(tool.execute(Map.of(), ctx).success());
    }

    @Test
    void filterKeyOutsideWhitelistIsRejected() {
        ToolResult r = tool.execute(Map.of("entity", "dormitory",
                "filters", Map.of("password", "123")), ctx);

        assertFalse(r.success());
        assertTrue(r.content().contains("不支持的字段"), "应指出非法字段：" + r.content());
        verify(dormitoryMapper, never()).selectList(any());
    }

    @Test
    void injectionValueIsTreatedAsLiteralAndYieldsNoRows() {
        // 白名单内的键，但值是注入串 → 走参数化 eq → 匹配不到任何行（Mapper 返回空）
        ToolResult r = tool.execute(Map.of("entity", "dormitory",
                "filters", Map.of("name", "' OR 1=1 --")), ctx);

        assertTrue(r.success(), "注入串不是参数错误，只是一次匹配不到的值");
        assertTrue(r.content().contains("未查询到"), "应返回可读的'未查到'：" + r.content());
        verify(dormitoryMapper, times(1)).selectList(any());
    }

    @Test
    void resultRowsAreCappedAt20() {
        List<Dormitory> many = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            many.add(dormitory("宿舍" + i, "四人间", BigDecimal.valueOf(1200), "空调"));
        }
        when(dormitoryMapper.selectList(any())).thenReturn(many);

        ToolResult r = tool.execute(Map.of("entity", "dormitory"), ctx);

        assertTrue(r.success());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) r.meta().get("rows");
        assertEquals(20, rows.size(), "结果行数上限应为 20");
        assertEquals(30, r.meta().get("rowCount"), "总数仍应如实汇报");
        assertTrue(r.content().contains("仅返回前 20 条"));
    }

    @Test
    void longTextFieldIsTruncated() {
        String longText = "很长的设施说明".repeat(200);   // 1400 字
        when(dormitoryMapper.selectList(any())).thenReturn(List.of(
                dormitory("厚德学区", "四人间", BigDecimal.valueOf(1200), longText)));

        ToolResult r = tool.execute(Map.of("entity", "dormitory"), ctx);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) r.meta().get("rows");
        String facilities = String.valueOf(rows.get(0).get("facilities"));
        assertTrue(facilities.length() <= 501, "单字段应截断到 500 字左右，实际 " + facilities.length());
        assertTrue(facilities.endsWith("…"));
    }

    /** 个人信息绝不外泄给第三方 LLM */
    @Test
    void clubPiiFieldsAreNotExposed() {
        Club c = new Club();
        c.setName("计算机协会");
        c.setCategory("学术科技");
        c.setDescription("社团简介");
        c.setPresident("张三");
        c.setContact("13800000000");
        c.setMemberCount(120);
        c.setRecruitInfo("每周三招新");
        c.setActivityTime("周三晚");
        c.setLocation("实验楼");
        c.setStatus(1);
        when(clubMapper.selectList(any())).thenReturn(List.of(c));

        ToolResult r = tool.execute(Map.of("entity", "club"), ctx);

        String whole = r.content() + r.meta().toString();
        assertFalse(whole.contains("张三"), "社团负责人姓名不得外泄：" + whole);
        assertFalse(whole.contains("13800000000"), "联系方式不得外泄：" + whole);
        assertTrue(whole.contains("计算机协会"), "非敏感字段应正常返回");
    }

    @Test
    void emptyResultReturnsReadableMessageNotFailure() {
        ToolResult r = tool.execute(Map.of("entity", "cafeteria", "filters", Map.of("name", "不存在")), ctx);

        assertTrue(r.success(), "查不到不是工具故障，应让模型据此如实回答");
        assertTrue(r.content().contains("未查询到"));
        assertEquals(0, r.meta().get("rowCount"));
    }
}
