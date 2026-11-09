package com.github.tvbox.osc.util;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 本地生成快速搜索的候选关键词。
 *
 * <p>此前这一步调用第三方分词接口 api.yesapi.cn，把用户正在查看的片名明文外发，
 * 且请求里带着硬编码的 app_key/sign。片名属于观看行为，外发与隐私声明冲突；
 * 快速搜索只是拿几个候选词去各源检索，完全不需要真正的语义分词。
 *
 * <p>本实现只做确定性的裁剪：去掉季集、剧场版一类的修饰，按分隔符切分，
 * 保留原标题作为首选。全程不产生任何网络请求。
 */
public final class SearchKeywords {

    /** 季/集/版本等修饰，出现在标题尾部时可裁掉 */
    private static final Pattern TRAILING_QUALIFIER = Pattern.compile(
            "(第[0-9一二三四五六七八九十百]+[季部篇集])|" +
            "([0-9]{4}年?版?)|" +
            "(剧场版|真人版|国语版|粤语版|完结篇|特别篇|番外篇|未删减版?|加长版|导演剪辑版)" +
            "\\s*$");

    /** 常见分隔符：中英文标点与空白 */
    private static final Pattern SEPARATOR = Pattern.compile("[\\s:：·・,，。、;；!！?？\\-—_/|（）()\\[\\]【】\"“”]+");

    private static final int MAX_KEYWORDS = 5;
    private static final int MIN_LENGTH = 2;

    private SearchKeywords() {
    }

    /**
     * @param title 影片标题
     * @return 候选关键词，原标题始终排在第一位；不会返回 null
     */
    public static List<String> from(String title) {
        List<String> result = new ArrayList<>();
        if (title == null || title.isEmpty()) return result;

        String normalized = title.trim();
        Set<String> candidates = new LinkedHashSet<>();
        candidates.add(normalized);

        // 逐层裁掉尾部修饰，"某剧 第二季" -> "某剧"
        String trimmed = normalized;
        for (int i = 0; i < 3; i++) {
            String next = TRAILING_QUALIFIER.matcher(trimmed).replaceAll("").trim();
            if (next.equals(trimmed)) break;
            trimmed = next;
            if (trimmed.length() >= MIN_LENGTH) candidates.add(trimmed);
        }

        // 按分隔符切分，取较长的片段
        for (String part : SEPARATOR.split(trimmed)) {
            String piece = part.trim();
            if (piece.length() >= MIN_LENGTH) candidates.add(piece);
        }

        for (String candidate : candidates) {
            if (result.size() >= MAX_KEYWORDS) break;
            if (candidate.length() >= MIN_LENGTH) result.add(candidate);
        }
        if (result.isEmpty()) result.add(normalized);
        return result;
    }
}
