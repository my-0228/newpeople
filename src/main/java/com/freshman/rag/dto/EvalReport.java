package com.freshman.rag.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * RAG 评估报告
 *
 * 检索指标（成本为零，不需要 LLM）：
 *  - hit1 / hit3：期望材料是否出现在第 1 / 前 3 位
 *  - mrr        ：期望材料排名的倒数均值
 *  - baselineHit1：**改造前基线**（LocalKnowledgeEngine 的最佳命中的倒数均值/命中率）
 *
 * 拒答指标（需真实 LLM；分两层，见计划二 Task 7 的实测校准）：
 *  - gateRefusalRate    ：门限级拒答率（真正无关的问题）
 *  - honestDeclarationRate：prompt 级诚实声明率（过门限但无覆盖 → 明说"未收录"）
 *  - fabricationSuspect  ：既未拒答也未声明未收录的负样本数（**期望 0，最关键**）
 *
 * 所属模块：AI 智能问答模块 / RAG
 */
@Data
public class EvalReport {

    /** 评估集条目数 */
    private int itemCount;

    /** 可答题数 / 负样本数 */
    private int answerableCount;
    private int negativeCount;

    /** Hit@1 */
    private double hit1;

    /** Hit@3 */
    private double hit3;

    /** MRR（期望材料排名倒数均值） */
    private double mrr;

    /** 改造前基线（本地引擎）的 Hit@1 */
    private double baselineHit1;

    /** 门限级拒答数（负样本中） */
    private int gateRefusalCount;

    /** 诚实声明数（负样本中，过门限但明说"未收录"） */
    private int honestDeclarationCount;

    /** 疑似编造数（负样本中既未拒答也未声明未收录）—— 期望 0 */
    private int fabricationSuspectCount;

    /** 未解析到 chunk 的期望 ref（应为空；非空说明语料变动导致标注失效） */
    private List<String> unresolvableRefs = new ArrayList<>();

    /** 逐条明细，供 Markdown 报告使用 */
    private List<ItemResult> items = new ArrayList<>();

    /** 单条评估结果 */
    @Data
    public static class ItemResult {
        private int id;
        private String question;
        private boolean answerable;
        /** 期望 ref 在改造后检索里的名次（1 起；未命中为 0） */
        private int ragRank;
        /** 改造前本地引擎是否命中该 ref */
        private boolean baselineHit;
        /** 拒答层判定：GATE_REFUSAL / HONEST / SUSPECT / ANSWERED / NOT_RUN */
        private String refusalLayer = "NOT_RUN";
        /** 实际返回的引用/材料 ref，便于人工核对 */
        private String topRefs;

        /** 本次检索实际使用的门限模式（vector / keyword），便于定位"为什么没取到材料" */
        private String gateMode;

        /** 本次检索的 top1 原始分（不是 RRF 分） */
        private double topScore;
    }
}
