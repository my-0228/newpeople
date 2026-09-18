package com.freshman.rag.tokenizer;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ChineseTokenizer 单元测试。
 *
 * 这些测试的作用不只是"测新类"，更是**把改造前 AiQaServiceImpl 的分词行为钉住** ——
 * 抽取是零行为变更的重构，所以断言必须反映原实现的语义（bigram/trigram、正向最大匹配、
 * 同义词一层扩展、单字兜底）。
 */
class ChineseTokenizerTest {

    private final ChineseTokenizer tokenizer = new ChineseTokenizer();

    @Test
    void ngramCoversBigramAndTrigram() {
        List<String> t = tokenizer.tokenize("宿舍有空调吗");
        assertTrue(t.contains("宿舍"), "应含 bigram：宿舍");
        assertTrue(t.contains("宿舍有"), "应含 trigram：宿舍有");
        assertTrue(t.contains("空调"), "应含 bigram：空调");
    }

    @Test
    void singleCharactersAreAlsoEmitted() {
        List<String> t = tokenizer.tokenize("空调");
        assertTrue(t.contains("空"), "单字兜底应输出：空");
        assertTrue(t.contains("调"), "单字兜底应输出：调");
    }

    @Test
    void dictionaryMaxMatchPrefersLongerWord() {
        // 词典含"录取通知书"时，应匹配整词（最长匹配），而不是退化成"录取"/"通知"
        List<String> t = tokenizer.tokenize("录取通知书丢失", List.of("录取通知书"));
        assertTrue(t.contains("录取通知书"), "正向最大匹配应命中最长词：" + t);
    }

    @Test
    void registerTermsFeedsDictionaryForSubsequentTokenize() {
        // 这条覆盖 AiQaServiceImpl 的用法：知识库关键词先注册，再分词
        tokenizer.registerTerms(List.of("厚德学区,物业费", "寝室,住宿"));
        List<String> t = tokenizer.tokenize("厚德学区物业费多少");
        assertTrue(t.contains("厚德学区"), "注册的词条应进入词典：" + t);
        assertTrue(t.contains("物业费"));
    }

    @Test
    void clearTermsRemovesRegisteredTerms() {
        tokenizer.registerTerms(List.of("厚德学区"));
        tokenizer.clearTerms();
        List<String> t = tokenizer.tokenize("厚德学区物业费多少");
        assertFalse(t.contains("厚德学区"), "清空后不应再命中：" + t);
    }

    @Test
    void synonymsAreExpandedFromEitherDirection() {
        // 同义词 → 标准词
        assertTrue(tokenizer.expandSynonyms(List.of("寝室")).contains("宿舍"),
                "寝室 应扩展出标准词 宿舍");
        // 标准词 → 其同义词
        assertTrue(tokenizer.expandSynonyms(List.of("宿舍")).contains("寝室"),
                "宿舍 应扩展出其同义词");
    }

    @Test
    void synonymExpansionIsSingleLevelNotCascading() {
        // 与原实现一致：只对**原始 token** 做一层扩展，不做级联
        List<String> out = tokenizer.expandSynonyms(List.of("寝室"));
        assertTrue(out.contains("寝室"));
        assertTrue(out.contains("宿舍"));
        // "宿舍" 的同义词（如"公寓"）不应因为"宿舍"是扩展出来的而被继续展开
        assertFalse(out.contains("公寓"),
                "不应级联扩展：原实现只扩展原始 token，实际=" + out);
    }

    @Test
    void blankAndNullInputAreSafe() {
        assertTrue(tokenizer.tokenize("").isEmpty());
        assertTrue(tokenizer.tokenize(null).isEmpty());
        assertTrue(tokenizer.expandSynonyms(null).isEmpty());
        List<String> blank = tokenizer.tokenize("   ");
        assertTrue(blank.isEmpty(), "纯空白不应产生 token，实际=" + blank);
    }

    @Test
    void punctuationIsStrippedFromNgrams() {
        List<String> t = tokenizer.tokenize("宿舍，有空调！");
        assertFalse(t.contains("舍，"), "标点不应出现在 n-gram 中：" + t);
        assertTrue(t.contains("宿舍"));
    }
}
