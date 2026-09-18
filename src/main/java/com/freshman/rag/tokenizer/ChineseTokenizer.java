package com.freshman.rag.tokenizer;

import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 中文分词器 + 同义词扩展（从 AiQaServiceImpl 原样抽出，**算法一行未改**）
 *
 * 抽出的目的：让"本地匹配引擎"（降级路径）与"关键词检索路径"共用同一套分词与同义词逻辑，
 * 避免同一能力出现两套实现（这是本项目面试文档里被点名过的缺陷）。
 *
 * 三种分词策略（与改造前一致）：
 *  1. 字符级 N-gram：bigram + trigram
 *  2. 词典正向最大匹配（最长 6 字）：词典 = 知识库关键词/同义词 ∪ 同义词字典
 *  3. 单字兜底（对短问题有帮助）
 *
 * 所属模块：AI 智能问答模块 / RAG
 * @author AI Module Team
 * @version 1.0
 */
@Component
public class ChineseTokenizer {

    /** 同义词字典：标准词 → 同义表达（原 AiQaServiceImpl.SYNONYM_DICT，一字未改） */
    private static final Map<String, List<String>> SYNONYM_DICT = new LinkedHashMap<>();

    static {
        // 宿舍相关
        SYNONYM_DICT.put("宿舍", Arrays.asList("寝室", "住宿", "公寓", "住的地方", "房间", "住房"));
        SYNONYM_DICT.put("空调", Arrays.asList("冷气", "制冷", "降温设备"));
        SYNONYM_DICT.put("独卫", Arrays.asList("独立卫生间", "独立卫浴", "单独厕所", "独立厕所", "私人卫生间"));
        // 军训相关
        SYNONYM_DICT.put("军训", Arrays.asList("军事训练", "军训服", "迷彩服", "军服", "队列训练"));
        SYNONYM_DICT.put("请假", Arrays.asList("请病假", "不参加", "免训", "缓训", "休息"));
        // 费用相关
        SYNONYM_DICT.put("学费", Arrays.asList("学费多少钱", "缴费标准", "收费", "要交多少钱"));
        SYNONYM_DICT.put("奖学金", Arrays.asList("助学金", "补助", "资助", "奖励", "奖金", "困难补助"));
        SYNONYM_DICT.put("贷款", Arrays.asList("助学贷款", "借钱上学", "借款", "分期"));
        // 报到相关
        SYNONYM_DICT.put("报到", Arrays.asList("报名", "入学", "开学", "去学校", "注册", "签到"));
        SYNONYM_DICT.put("录取通知书", Arrays.asList("通知书", "录取通知", "录取书", "通知书丢了"));
        // 社团相关
        SYNONYM_DICT.put("社团", Arrays.asList("协会", "俱乐部", "组织", "团队", "学生组织"));
        // 生活相关
        SYNONYM_DICT.put("食堂", Arrays.asList("餐厅", "饭堂", "吃饭的地方", "伙食", "餐饮"));
        SYNONYM_DICT.put("WiFi", Arrays.asList("wifi", "网络", "上网", "无线网", "校园网", "宽带", "联网"));
        SYNONYM_DICT.put("图书馆", Arrays.asList("自习室", "看书的地方", "学习的地方", "借书处"));
        // 其他
        SYNONYM_DICT.put("老师", Arrays.asList("教师", "教授", "导员", "辅导员", "讲师"));
        SYNONYM_DICT.put("专业", Arrays.asList("学科", "方向", "学什么", "课程"));
        SYNONYM_DICT.put("就业", Arrays.asList("工作", "找工作", "毕业去向", "招聘", "求职", "薪资"));
    }

    /** 同义词反向索引：任意同义词/标准词 → 标准词 */
    private final Map<String, String> synonymReverseIndex = new HashMap<>();

    /** 动态词典：由知识库的关键词/同义词注册进来（原 buildTokenizerDictionary 的语料） */
    private final Set<String> dynamicDict = ConcurrentHashMap.newKeySet();

    public ChineseTokenizer() {
        buildSynonymReverseIndex();
    }

    /** 构建同义词反向索引（原逻辑：每个同义词映射到标准词，标准词映射到自己） */
    private void buildSynonymReverseIndex() {
        for (Map.Entry<String, List<String>> entry : SYNONYM_DICT.entrySet()) {
            String standard = entry.getKey();
            for (String synonym : entry.getValue()) {
                synonymReverseIndex.put(synonym, standard);
            }
            synonymReverseIndex.put(standard, standard);
        }
    }

    /** 注册知识库关键词/同义词（长度 >= 2 的才进词典，与原逻辑一致） */
    public void registerTerms(Collection<String> rawTerms) {
        if (rawTerms == null) {
            return;
        }
        for (String term : rawTerms) {
            if (term == null) {
                continue;
            }
            for (String piece : term.split("[,，]")) {
                String trimmed = piece.trim();
                if (trimmed.length() >= 2) {
                    dynamicDict.add(trimmed);
                }
            }
        }
    }

    /** 清空动态词典（索引重建前调用） */
    public void clearTerms() {
        dynamicDict.clear();
    }

    /** 同义词字典的全部词条（键 + 值），供词典构建复用 */
    public Set<String> synonymTerms() {
        Set<String> all = new HashSet<>();
        for (Map.Entry<String, List<String>> e : SYNONYM_DICT.entrySet()) {
            all.add(e.getKey());
            all.addAll(e.getValue());
        }
        return all;
    }

    public List<String> tokenize(String text) {
        return tokenize(text, Set.of());
    }

    /**
     * 分词。字典 = 动态词典 ∪ 同义词字典 ∪ extraDict。
     *
     * @param text      待分词文本
     * @param extraDict 额外的词典条目（如知识库关键词），可为空
     */
    public List<String> tokenize(String text, Collection<String> extraDict) {
        Set<String> tokens = new LinkedHashSet<>();
        if (text == null || text.isEmpty()) {
            return new ArrayList<>(tokens);
        }

        // 策略1：字符级 N-gram（bigram + trigram）
        String cleaned = text.replaceAll("[\\s\\p{Punct}，。！？；：“”''【】《》（）…—～·]", "");
        for (int i = 0; i < cleaned.length() - 1; i++) {
            tokens.add(cleaned.substring(i, i + 2));           // bigram
            if (i + 2 < cleaned.length()) {
                tokens.add(cleaned.substring(i, i + 3));       // trigram
            }
        }

        // 策略2：词典正向最大匹配（最长 6 字）
        Set<String> dictionary = buildDictionary(extraDict);
        int pos = 0;
        int textLen = text.length();
        while (pos < textLen) {
            int maxMatchLen = 0;
            String matched = null;
            for (int len = Math.min(6, textLen - pos); len >= 2; len--) {
                String candidate = text.substring(pos, pos + len);
                if (dictionary.contains(candidate)) {
                    maxMatchLen = len;
                    matched = candidate;
                    break;
                }
            }
            if (matched != null) {
                tokens.add(matched);
                pos += maxMatchLen;
            } else {
                pos++;
            }
        }

        // 策略3：单字兜底
        for (char ch : cleaned.toCharArray()) {
            if (Character.isLetterOrDigit(ch) || Character.UnicodeScript.of(ch).name().equals("HAN")) {
                tokens.add(String.valueOf(ch));
            }
        }

        return new ArrayList<>(tokens);
    }

    /** 词典 = 动态词典 ∪ 同义词字典 ∪ extraDict */
    private Set<String> buildDictionary(Collection<String> extraDict) {
        Set<String> dict = new HashSet<>(dynamicDict);
        dict.addAll(synonymTerms());
        if (extraDict != null) {
            for (String t : extraDict) {
                if (t != null && t.length() >= 2) {
                    dict.add(t);
                }
            }
        }
        return dict;
    }

    /**
     * 同义词扩展：把口语化表达映射到标准词（原逻辑：反向索引命中即加入；
     * 若词本身是标准词，则把它的所有同义词也加入）。
     */
    public List<String> expandSynonyms(List<String> tokens) {
        Set<String> expanded = new LinkedHashSet<>(tokens == null ? List.of() : tokens);
        if (tokens == null) {
            return new ArrayList<>(expanded);
        }
        // 只对**原始 token** 做一层扩展，不做级联 —— 与原逻辑严格一致
        for (String token : tokens) {
            if (synonymReverseIndex.containsKey(token)) {
                expanded.add(synonymReverseIndex.get(token));
            }
            if (SYNONYM_DICT.containsKey(token)) {
                expanded.addAll(SYNONYM_DICT.get(token));
            }
        }
        return new ArrayList<>(expanded);
    }
}
