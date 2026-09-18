package com.freshman.agent;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Agent 配置（前缀 app.ai.deepseek.agent）
 *
 * 项目**没有** @ConfigurationPropertiesScan（FreshmanApplication 仅 @MapperScan），
 * 因此必须显式 @Component 才会生效。
 *
 * 所属模块：DeepSeek 问答 / Agent
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.ai.deepseek.agent")
public class AgentProperties {

    /** 总开关；false 时 /deepseek-chat 行为与改造前一致（可回退） */
    private boolean enabled = true;

    /** 循环步数上限（防模型陷入工具循环烧 token） */
    private int maxSteps = 5;

    /** 单次提问累计 token 预算，超限即停止并返回已有结果 */
    private int tokenBudget = 30000;

    /** 单个工具结果回填给模型的最大字符数，超出截断（防 token 爆炸） */
    private int toolResultMaxChars = 4000;

    /** 携带的历史轮数（每轮 = 一问一答） */
    private int historyRounds = 3;

    /** 是否记录工具调用轨迹（agent_tool_call_log） */
    private boolean traceEnabled = true;
}
