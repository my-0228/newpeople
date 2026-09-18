package com.freshman.service;

// ---------- Jackson（JSON 解析） ----------
import com.fasterxml.jackson.databind.JsonNode;     // JSON 树节点：解析响应后逐级取值
import com.fasterxml.jackson.databind.ObjectMapper; // Jackson 核心对象：JSON字符串 ↔ JsonNode 树
// ---------- Spring ----------
import org.springframework.beans.factory.annotation.Value; // 读取环境变量/配置项
import org.springframework.stereotype.Service;             // 声明为 Service 层 Bean，交由容器管理
import org.springframework.web.client.RestTemplate;        // Spring REST 客户端，发起 HTTP 请求

import java.util.ArrayList;
import java.util.List;

/**
 * 百度地图导航服务
 * 功能：调用百度地图 Agent Plan Direction API 获取真实步行路线
 *
 * 【坐标体系说明】
 * 前端浏览器定位拿到的是 GPS 原始坐标（WGS-84 世界大地坐标系），而国内地图
 * 服务统一使用国测局加密的 GCJ-02（火星坐标系），两者存在与位置相关的非线性
 * 偏移（境内约 100~700 米）。因此本服务在请求前/解析后做双向转换：
 *   请求前：WGS-84 → GCJ-02（wgs84ToGcj02），保证百度按真实位置算路；
 *   解析后：GCJ-02 → WGS-84（gcj02ToWgs84），返回给前端的路线与原始定位
 *          处于同一坐标系，才能在同一张地图上正确绘制。
 *
 * 【降级策略】
 * API 调用异常 / 返回非路线应答 / 无有效坐标点时，降级为"起终点直线 +
 * Haversine 距离估算"（fallbackRoute），保证导航展示层始终有数据可渲染。
 *
 * 负责成员：Z
 * 所属模块：校园导览 / 智能导航
 */
@Service
public class BaiduNavigationService {

    /** 百度地图智能路线规划（Agent Plan）接口地址：接收自然语言指令，返回结构化路线 */
    private static final String DIRECTION_API = "https://api.map.baidu.com/agent_plan/v1/direction";

    /** REST 客户端：发起对百度 API 的 GET 请求（实例内新建，未走 Spring 统一配置的 Bean） */
    private final RestTemplate restTemplate = new RestTemplate();

    /** ObjectMapper：把 API 返回的 JSON 字符串解析为 JsonNode 树，便于容错取值 */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 百度地图鉴权令牌（Bearer Token），来源见构造器说明 */
    private final String token;

    /**
     * 构造器注入令牌。
     * @Value("${BAIDU_MAP_AUTH_TOKEN:#{null}}")：优先读取环境变量/配置中的
     * BAIDU_MAP_AUTH_TOKEN；未配置时通过 SpEL 默认值 #{null} 注入 null，
     * 避免占位符解析失败导致应用启动报错。
     */
    public BaiduNavigationService(@Value("${BAIDU_MAP_AUTH_TOKEN:#{null}}") String token) {
        this.token = token;
    }

    /**
     * 获取步行路线
     * 主流程：坐标转换 → 拼自然语言请求 → 调百度API → 解析路线坐标 → 组装结果
     *
     * @param fromLat 起点纬度 (WGS-84)
     * @param fromLng 起点经度 (WGS-84)
     * @param toLat   终点纬度 (WGS-84)
     * @param toLng   终点经度 (WGS-84)
     * @param toName  终点建筑名
     * @return 路线结果（坐标点列表、距离米、时间秒）；百度返回业务错误(status≠0)
     *         或响应缺 result 节点时返回 null，其余异常降级为直线兜底结果
     */
    public RouteResult getWalkingRoute(double fromLat, double fromLng,
                                        double toLat, double toLng,
                                        String toName) {
        // 兼容改造前的调用方（NavigationController）：起点名义上就是"我的位置"
        return getWalkingRoute(fromLat, fromLng, "我的位置", toLat, toLng, toName);
    }

    /**
     * 获取步行路线（可指定起点名称）
     *
     * 与 5 参版本的唯一区别：把自然语言指令由"从我的位置步行到X"改为"从{fromName}步行到X"，
     * 使 Agent 的 plan_route 工具在起点不是用户当前位置时也能给出正确语义。
     * 坐标换算、三级容灾等逻辑与原来完全一致。
     *
     * @param fromName 起点名称（为空时回退为"我的位置"）
     */
    public RouteResult getWalkingRoute(double fromLat, double fromLng, String fromName,
                                        double toLat, double toLng,
                                        String toName) {
        try {
            // ① 坐标转换：WGS-84（GPS 原始）→ GCJ-02（国测局火星坐标）
            //    百度接口按 GCJ-02 解释入参坐标；不转换会导致起点整体偏移数百米
            double[] fromGcj = wgs84ToGcj02(fromLat, fromLng);
            double[] toGcj = wgs84ToGcj02(toLat, toLng);

            // ② 构造自然语言请求与 URL
            //    该接口为"智能规划"型：直接接收自然语言指令，由服务端理解并生成路线
            //    终点名为空时用"目的地"兜底，保证指令语句通顺
            String fromLabel = (fromName == null || fromName.isBlank()) ? "我的位置" : fromName;
            String request = String.format("从%s步行到%s", fromLabel, toName.isEmpty() ? "目的地" : toName);
            String url = String.format("%s?user_raw_request=%s&location=%.6f,%.6f",
                    DIRECTION_API,
                    java.net.URLEncoder.encode(request, "UTF-8"), // 中文指令必须 URL 编码
                    fromGcj[0], fromGcj[1]);                      // location=起点(GCJ-02)，保留6位小数

            // ③ 第一次调用 —— 冗余遗留代码（返回值随后被 ⑤ 覆盖，可删除）
            //    问题1：把 HttpHeaders.AUTHORIZATION 与令牌当作 URI 模板变量传入，
            //           但 URL 中并无 {占位符}，这两个参数实际被忽略；
            //    问题2：因此本次请求并未携带 Authorization 请求头；
            //    真正生效的请求是下方 ⑤ 的 restTemplate.exchange(...)。
            String response = restTemplate.getForObject(url, String.class,
                    org.springframework.http.HttpHeaders.AUTHORIZATION, "Bearer " + token);

            // ④ 构造带鉴权头的请求实体：Authorization: Bearer <token> 放入请求头
            org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
            headers.set("Authorization", "Bearer " + token);
            var entity = new org.springframework.http.HttpEntity<>(headers);

            var exchangeUrl = url; // 临时变量（当前未使用，遗留代码）
            // （开发期遗留注释：后来改用了更简单的写法）
            // ⑤ 重新拼接 URL（与 ② 等价，写法更直白）后发起真正的请求
            url = DIRECTION_API
                    + "?user_raw_request=" + java.net.URLEncoder.encode(request, "UTF-8")
                    + "&location=" + String.format("%.6f,%.6f", fromGcj[0], fromGcj[1]);

            // exchange() 可携带自定义请求头（getForObject 做不到），这才是实际生效的调用
            var responseEntity = restTemplate.exchange(
                    url, org.springframework.http.HttpMethod.GET, entity, String.class);
            response = responseEntity.getBody(); // 覆盖 ③ 的返回值

            // ⑥ 解析响应：把 JSON 字符串读成树
            JsonNode root = objectMapper.readTree(response);
            // 百度约定 status=0 为成功；非 0 均为业务错误（令牌无效、配额不足等）
            int status = root.get("status").asInt();
            if (status != 0) {
                System.err.println("百度导航API错误: status=" + status + ", msg=" + root.get("message"));
                return null; // 业务错误直接返回 null，交由上层调用方处理
            }

            // result 节点缺失视为异常响应
            JsonNode result = root.get("result");
            if (result == null) return null;

            // ⑦ 校验应答类型：answer_type=gptmodel_navigate 才是路线结果
            //    其他值（如地点澄清/反问）说明服务端没给出路线 → 直线兜底
            String answerType = result.get("answer_type") != null ? result.get("answer_type").asText() : "";
            if (!"gptmodel_navigate".equals(answerType)) {
                // 可能是地点澄清，走直线兜底
                return fallbackRoute(fromLat, fromLng, toLat, toLng);
            }

            // ⑧ 取第一条路线，读取总距离（米）与总耗时（秒）
            JsonNode routes = result.get("routes");
            if (routes == null || !routes.isArray() || routes.size() == 0) {
                return fallbackRoute(fromLat, fromLng, toLat, toLng); // 无路线数据 → 兜底
            }

            JsonNode route = routes.get(0);
            double distanceMeters = route.get("distance") != null ? route.get("distance").asDouble() : 0;
            double durationSec = route.get("duration") != null ? route.get("duration").asDouble() : 0;

            // ⑨ 遍历路线步骤，收集前端绘线所需的坐标点
            //    每个步骤可贡献三类点：起点、终点、path 细化轨迹点（若有）；
            //    所有坐标均为 GCJ-02，需逐个转回 WGS-84 再返回
            List<double[]> points = new ArrayList<>();
            JsonNode steps = route.get("steps");
            if (steps != null && steps.isArray()) {
                for (JsonNode step : steps) {
                    // 步骤起点坐标
                    JsonNode start = step.get("start_location");
                    if (start != null) {
                        double lat = start.get("lat").asDouble();
                        double lng = start.get("lng").asDouble();
                        double[] wgs = gcj02ToWgs84(lat, lng); // GCJ-02 → WGS-84
                        points.add(new double[]{wgs[0], wgs[1]});
                    }
                    // 步骤终点坐标
                    JsonNode end = step.get("end_location");
                    if (end != null) {
                        double lat = end.get("lat").asDouble();
                        double lng = end.get("lng").asDouble();
                        double[] wgs = gcj02ToWgs84(lat, lng);
                        points.add(new double[]{wgs[0], wgs[1]});
                    }
                    // 步骤细化轨迹点：path 存在时逐点转换，绘出的折线更贴近真实道路
                    JsonNode path = step.get("path");
                    if (path != null && path.isArray()) {
                        for (JsonNode p : path) {
                            double lat = p.get("lat").asDouble();
                            double lng = p.get("lng").asDouble();
                            double[] wgs = gcj02ToWgs84(lat, lng);
                            points.add(new double[]{wgs[0], wgs[1]});
                        }
                    }
                }
            }

            // ⑩ 一个坐标点都没解析出来 → 视为无效路线，直线兜底
            if (points.isEmpty()) {
                return fallbackRoute(fromLat, fromLng, toLat, toLng);
            }

            // ⑪ 组装结果：距离/耗时优先用 API 返回值，缺失时本地估算
            RouteResult rr = new RouteResult();
            rr.points = points;
            // API 未给距离 → 用 Haversine 直线距离估算
            rr.distanceMeters = distanceMeters > 0 ? distanceMeters : calcDistance(fromLat, fromLng, toLat, toLng);
            // API 未给耗时 → 按成人步行速度 1.3 m/s 折算
            rr.durationSeconds = durationSec > 0 ? durationSec : rr.distanceMeters / 1.3;
            return rr;

        } catch (Exception e) {
            // 任何异常（网络不通、JSON 解析失败等）都不向外抛，统一直线兜底，
            // 保证前端始终能拿到可绘制的导航结果
            e.printStackTrace();
            return fallbackRoute(fromLat, fromLng, toLat, toLng);
        }
    }

    /**
     * 直线兜底方案
     * 场景：百度 API 调用失败 / 返回非路线应答 / 无有效坐标点。
     * 结果：起终点两点连线（仅 2 个坐标点），距离用 Haversine 球面距离，
     *       耗时按步行速度 1.3 m/s 折算，保证导航展示层始终有数据可渲染。
     */
    private RouteResult fallbackRoute(double fromLat, double fromLng, double toLat, double toLng) {
        double dist = calcDistance(fromLat, fromLng, toLat, toLng); // 球面直线距离（米）
        RouteResult rr = new RouteResult();
        rr.points = List.of(new double[]{fromLat, fromLng}, new double[]{toLat, toLng}); // 仅起终点两个端点
        rr.distanceMeters = dist;
        rr.durationSeconds = dist / 1.3; // 1.3 m/s ≈ 成人正常步行速度
        return rr;
    }

    /**
     * WGS-84 → GCJ-02（GPS 原始坐标 → 国测局火星坐标）
     * 原理：GCJ-02 是对 WGS-84 施加与位置相关的非线性偏移得到的加密坐标系，
     * 此处按国测局公开的近似算法计算偏移量并叠加（正向转换 = 加偏移）。
     *
     * @return 转换后坐标 [lat, lng]；境外坐标（无需偏移）原样返回
     */
    public static double[] wgs84ToGcj02(double lat, double lng) {
        // 先判断是否在中国境内：粗略矩形范围（经 72.004~137.8347，纬 0.8293~55.8271）
        // 境外坐标不做偏移——GCJ-02 加密仅针对境内数据
        if (lng < 72.004 || lng > 137.8347 || lat < 0.8293 || lat > 55.8271) {
            return new double[]{lat, lng};
        }
        double[] d = delta(lat, lng); // 计算该位置的经纬度偏移量
        return new double[]{lat + d[0], lng + d[1]}; // 正向转换：加上偏移
    }

    /**
     * GCJ-02 → WGS-84（火星坐标 → GPS 原始坐标）
     * 采用"反向偏移"近似算法：用同一偏移量在反方向做减法，
     * 误差为米级，满足校园导航的精度要求（反向转换 = 减偏移）。
     *
     * @return 转换后坐标 [lat, lng]；境外坐标原样返回
     */
    public static double[] gcj02ToWgs84(double lat, double lng) {
        if (lng < 72.004 || lng > 137.8347 || lat < 0.8293 || lat > 55.8271) {
            return new double[]{lat, lng};
        }
        double[] d = delta(lat, lng);
        return new double[]{lat - d[0], lng - d[1]}; // 反向转换：减去偏移
    }

    /**
     * 计算指定位置 (lat, lng) 处 WGS-84↔GCJ-02 的经纬度偏移量 [dLat, dLng]
     * 基于 Krasovsky 1940 椭球参数的国测局标准近似算法：
     *   a  = 6378245.0                  椭球长半轴（米）
     *   ee = 0.00669342162296594323     第一偏心率平方
     * 偏移量先由 transformLat/transformLng 多项式拟合，再经椭球曲率修正换算为度
     */
    private static double[] delta(double lat, double lng) {
        double pi = Math.PI;
        double a = 6378245.0;               // 克拉索夫斯基椭球长半轴（米）
        double ee = 0.00669342162296594323; // 第一偏心率平方
        // 经验多项式：以（经度-105, 纬度-35）为自变量（约为中国版图几何中心）
        double dLat = transformLat(lng - 105.0, lat - 35.0);
        double dLng = transformLng(lng - 105.0, lat - 35.0);
        double radLat = lat / 180.0 * pi;   // 纬度转弧度
        // 以下按椭球法线曲率，把拟合出的偏移量换算为经纬度度数
        double magic = Math.sin(radLat);
        magic = 1 - ee * magic * magic;     // 子午圈曲率因子
        double sqrtMagic = Math.sqrt(magic);
        dLat = (dLat * 180.0) / ((a * (1 - ee)) / (magic * sqrtMagic) * pi); // 纬度偏移→度
        dLng = (dLng * 180.0) / (a / sqrtMagic * Math.cos(radLat) * pi);     // 经度偏移→度（随纬度增高而收敛）
        return new double[]{dLat, dLng};
    }

    /** 纬度偏移拟合多项式：三角函数叠加的经验公式（国测局 GCJ-02 算法组成部分） */
    private static double transformLat(double x, double y) {
        double ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * Math.sqrt(Math.abs(x));
        ret += (20.0 * Math.sin(6.0 * x * Math.PI) + 20.0 * Math.sin(2.0 * x * Math.PI)) * 2.0 / 3.0;
        ret += (20.0 * Math.sin(y * Math.PI) + 40.0 * Math.sin(y / 3.0 * Math.PI)) * 2.0 / 3.0;
        ret += (160.0 * Math.sin(y / 12.0 * Math.PI) + 320.0 * Math.sin(y * Math.PI / 30.0)) * 2.0 / 3.0;
        return ret;
    }

    /** 经度偏移拟合多项式：与 transformLat 同源的经验公式 */
    private static double transformLng(double x, double y) {
        double ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * Math.sqrt(Math.abs(x));
        ret += (20.0 * Math.sin(6.0 * x * Math.PI) + 20.0 * Math.sin(2.0 * x * Math.PI)) * 2.0 / 3.0;
        ret += (20.0 * Math.sin(x * Math.PI) + 40.0 * Math.sin(x / 3.0 * Math.PI)) * 2.0 / 3.0;
        ret += (150.0 * Math.sin(x / 12.0 * Math.PI) + 300.0 * Math.sin(x / 30.0 * Math.PI)) * 2.0 / 3.0;
        return ret;
    }

    /**
     * Haversine 球面距离：计算两经纬度点间的大圆距离（米）
     * R=6371000 为地球平均半径；公式先把经纬度差转为弧度，
     * 用半正矢公式求出两点相对地心的圆心角，再乘半径得弧长
     */
    private static double calcDistance(double lat1, double lng1, double lat2, double lng2) {
        double R = 6371000;                          // 地球平均半径（米）
        double dLat = (lat2 - lat1) * Math.PI / 180; // 纬度差 → 弧度
        double dLng = (lng2 - lng1) * Math.PI / 180; // 经度差 → 弧度
        // h = sin²(Δφ/2) + cosφ1·cosφ2·sin²(Δλ/2)
        double a = Math.sin(dLat/2)*Math.sin(dLat/2) +
                Math.cos(lat1*Math.PI/180)*Math.cos(lat2*Math.PI/180)*Math.sin(dLng/2)*Math.sin(dLng/2);
        // d = 2R·atan2(√h, √(1-h))：圆心角 × 半径 = 弧长
        return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1-a));
    }

    /**
     * 路线结果（返回给控制层/前端的载体）
     */
    public static class RouteResult {
        public List<double[]> points;      // 路线坐标点序列 [lat, lng]（WGS-84），按行进顺序排列
        public double distanceMeters;      // 总距离（米）
        public double durationSeconds;     // 预计耗时（秒）
    }
}
