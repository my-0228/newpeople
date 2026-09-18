package com.freshman.agent.tool;

import com.freshman.agent.AgentTool;
import com.freshman.agent.ToolContext;
import com.freshman.agent.ToolResult;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 通用工具（时间 / 算术）
 *
 * 用 @Configuration + 两个 @Bean 暴露，而不是一个类实现两次 AgentTool ——
 * 后者无法被 `List<AgentTool>` 正确收集（一个 Bean 只能有一个类型）。
 *
 * 为什么需要这两个工具：
 *  - 模型**无法自行知晓当前时间**（训练数据里没有"现在"），必须给它工具；
 *  - 日期加减与算术由模型"心算"容易出错，交给确定性代码。
 *
 * 所属模块：DeepSeek 问答 / Agent
 */
@Configuration
public class UtilityToolsConfig {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss EEEE");

    /** 当前时间工具（无参） */
    @Bean
    public AgentTool getCurrentTimeTool() {
        return new AgentTool() {
            @Override
            public String name() {
                return "get_current_time";
            }

            @Override
            public String description() {
                return "获取当前日期和时间（中国标准时间）。当用户问今天几号、星期几、"
                        + "或需要基于当前日期做推算时调用。";
            }

            @Override
            public Map<String, Object> parameters() {
                Map<String, Object> schema = new LinkedHashMap<>();
                schema.put("type", "object");
                schema.put("properties", Map.of());
                schema.put("required", java.util.List.of());
                return schema;
            }

            @Override
            public ToolResult execute(Map<String, Object> args, ToolContext ctx) {
                LocalDateTime now = LocalDateTime.now(ZONE);
                Map<String, Object> meta = new LinkedHashMap<>();
                meta.put("date", now.toLocalDate().toString());
                meta.put("datetime", now.format(FMT));
                return ToolResult.ok("当前时间：" + now.format(FMT), meta);
            }
        };
    }

    /** 算术工具（受限表达式求值，**不使用脚本引擎**） */
    @Bean
    public AgentTool calculateTool() {
        return new AgentTool() {
            @Override
            public String name() {
                return "calculate";
            }

            @Override
            public String description() {
                return "计算一个算术表达式，支持 + - * / 和括号，例如 \"14*2\" 或 \"(3+4)*2\"。"
                        + "涉及数字计算时应当调用本工具，不要自己心算。";
            }

            @Override
            public Map<String, Object> parameters() {
                Map<String, Object> props = new LinkedHashMap<>();
                props.put("expression", Map.of(
                        "type", "string",
                        "description", "算术表达式，只允许数字与 + - * / ( ) 及空格"));
                Map<String, Object> schema = new LinkedHashMap<>();
                schema.put("type", "object");
                schema.put("properties", props);
                schema.put("required", java.util.List.of("expression"));
                return schema;
            }

            @Override
            public ToolResult execute(Map<String, Object> args, ToolContext ctx) {
                Object raw = args == null ? null : args.get("expression");
                if (raw == null) {
                    return ToolResult.fail("缺少参数 expression");
                }
                String expr = String.valueOf(raw).trim();
                if (!isSafe(expr)) {
                    // 只接受数字与四则运算符 —— 字母、分号、下划线等一律拒绝
                    return ToolResult.fail("表达式含不支持的字符，只允许数字与 + - * / ( ) 及空格：" + expr);
                }
                try {
                    double v = new Evaluator(expr).parse();
                    String formatted = (v == Math.rint(v) && !Double.isInfinite(v))
                            ? String.valueOf((long) v) : String.valueOf(v);
                    return ToolResult.ok(expr + " = " + formatted);
                } catch (ArithmeticException e) {
                    return ToolResult.fail("计算失败：" + e.getMessage());
                } catch (Exception e) {
                    return ToolResult.fail("表达式无法解析：" + expr);
                }
            }
        };
    }

    /** 字符白名单：数字、四则运算符、括号、小数点、空格 */
    private static boolean isSafe(String expr) {
        if (expr.isEmpty() || expr.length() > 200) {
            return false;
        }
        for (char c : expr.toCharArray()) {
            boolean ok = Character.isDigit(c) || c == '+' || c == '-' || c == '*'
                    || c == '/' || c == '(' || c == ')' || c == '.' || c == ' ';
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    /**
     * 极简递归下降求值器（expr → term → factor）
     *
     * 刻意**不**用 Nashorn/脚本引擎：那些要么已被移除、要么能力过强（可执行任意代码），
     * 而这里只需要四则运算。语法：
     *   expr   := term (('+' | '-') term)*
     *   term   := factor (('*' | '/') factor)*
     *   factor := number | '(' expr ')' | '-' factor
     */
    static final class Evaluator {
        private final String s;
        private int pos;

        Evaluator(String s) {
            this.s = s;
        }

        double parse() {
            double v = expr();
            skipSpaces();
            if (pos < s.length()) {
                throw new IllegalArgumentException("表达式结尾有多余字符：" + s.substring(pos));
            }
            return v;
        }

        private double expr() {
            double v = term();
            while (true) {
                skipSpaces();
                if (eat('+')) {
                    v += term();
                } else if (eat('-')) {
                    v -= term();
                } else {
                    return v;
                }
            }
        }

        private double term() {
            double v = factor();
            while (true) {
                skipSpaces();
                if (eat('*')) {
                    v *= factor();
                } else if (eat('/')) {
                    double d = factor();
                    if (d == 0) {
                        throw new ArithmeticException("除数不能为 0");
                    }
                    v /= d;
                } else {
                    return v;
                }
            }
        }

        private double factor() {
            skipSpaces();
            if (eat('(')) {
                double v = expr();
                skipSpaces();
                if (!eat(')')) {
                    throw new IllegalArgumentException("缺少右括号");
                }
                return v;
            }
            if (eat('-')) {
                return -factor();
            }
            if (eat('+')) {
                return factor();
            }
            int start = pos;
            while (pos < s.length() && (Character.isDigit(s.charAt(pos)) || s.charAt(pos) == '.')) {
                pos++;
            }
            if (start == pos) {
                throw new IllegalArgumentException("期望数字，位置 " + pos);
            }
            return Double.parseDouble(s.substring(start, pos));
        }

        private boolean eat(char c) {
            skipSpaces();
            if (pos < s.length() && s.charAt(pos) == c) {
                pos++;
                return true;
            }
            return false;
        }

        private void skipSpaces() {
            while (pos < s.length() && s.charAt(pos) == ' ') {
                pos++;
            }
        }
    }
}
