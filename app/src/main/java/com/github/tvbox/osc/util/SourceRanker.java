package com.github.tvbox.osc.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 搜索结果与播放线路的排序规则（纯 Java，便于单元测试）。
 *
 * <p>搜索结果依次按：片名匹配度 → 站点本地质量分 → 本次搜索响应耗时 排序，同档保持到达顺序。
 * 质量分只用本机播放记录学习，不访问网络。
 */
public final class SourceRanker {

    /** 质量分的半衰期：30 天前的记录权重减半 */
    public static final long HALF_LIFE_MS = 30L * 24 * 60 * 60 * 1000;
    /** 没有起播耗时数据时按 3 秒估算 */
    private static final double UNKNOWN_FIRST_FRAME_MS = 3000;
    private static final double SPEED_SCALE_MS = 8000;

    private SourceRanker() {
    }

    /** 站点或线路的质量分来源 */
    public interface Quality {
        double score(String key);
    }

    /** 从结果对象里取片名和站点 key */
    public interface Keys<T> {
        String title(T item);

        String site(T item);
    }

    /** 去掉空白、标点和大小写差异，只保留字母数字（含中文） */
    public static String normalize(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                sb.append(Character.toLowerCase(c));
            }
        }
        return sb.toString();
    }

    /** 片名匹配度：2=完全一致，1=互相包含，0=其他 */
    public static int titleMatch(String query, String title) {
        String q = normalize(query);
        String t = normalize(title);
        if (q.isEmpty() || t.isEmpty()) return 0;
        if (q.equals(t)) return 2;
        if (t.contains(q) || (t.length() >= 2 && q.contains(t))) return 1;
        return 0;
    }

    /** 按半衰期计算的时间衰减系数 */
    public static double decay(long ageMs) {
        if (ageMs <= 0) return 1;
        return Math.pow(0.5, ageMs / (double) HALF_LIFE_MS);
    }

    /**
     * 质量分 = 贝叶斯平滑成功率 × 起播速度系数。
     * 没有任何记录时为 0.5 × 0.727 ≈ 0.36，即“中位分”：好站点排在它前面，常失败的排在它后面。
     */
    public static double qualityScore(double success, double fail, double avgFirstFrameMs) {
        double rate = (success + 1) / (success + fail + 2);
        double ms = avgFirstFrameMs > 0 ? avgFirstFrameMs : UNKNOWN_FIRST_FRAME_MS;
        return rate / (1 + ms / SPEED_SCALE_MS);
    }

    public static double unknownScore() {
        return qualityScore(0, 0, 0);
    }

    /** 排序搜索结果（稳定排序，返回新列表，不改动入参） */
    public static <T> List<T> rank(List<T> items, final Keys<T> keys, final String query,
                                   final Quality quality, final Map<String, Long> elapsedMs) {
        List<T> out = new ArrayList<>(items);
        Collections.sort(out, comparator(keys, query, quality, elapsedMs));
        return out;
    }

    /** 质量分分档宽度：同一档内交给响应耗时决定 */
    private static final double BUCKET = 0.02;

    /** 分档成整数再比较，保证比较器满足传递性（阈值比较会违反 TimSort 约定而抛异常） */
    public static int bucket(double score) {
        return (int) Math.floor(score / BUCKET);
    }

    public static <T> Comparator<T> comparator(final Keys<T> keys, final String query,
                                               final Quality quality, final Map<String, Long> elapsedMs) {
        // 每个站点只算一次分，排序过程中分数不会漂移
        final Map<String, Integer> buckets = new java.util.HashMap<>();
        return new Comparator<T>() {
            private int bucketOf(String site) {
                Integer b = buckets.get(site);
                if (b == null) {
                    b = quality == null ? 0 : bucket(quality.score(site));
                    buckets.put(site, b);
                }
                return b;
            }

            @Override
            public int compare(T a, T b) {
                int m = Integer.compare(titleMatch(query, keys.title(b)), titleMatch(query, keys.title(a)));
                if (m != 0) return m;
                int q = Integer.compare(bucketOf(keys.site(b)), bucketOf(keys.site(a)));
                if (q != 0) return q;
                return Long.compare(elapsed(elapsedMs, keys.site(a)), elapsed(elapsedMs, keys.site(b)));
            }
        };
    }

    private static long elapsed(Map<String, Long> map, String key) {
        if (map == null || key == null) return Long.MAX_VALUE;
        Long v = map.get(key);
        return v == null ? Long.MAX_VALUE : v;
    }

    /**
     * 线路排序：第一集是直链的线路在前，同档按线路历史质量分，再同档保持原顺序。
     *
     * @return 新顺序下各线路在原列表中的下标
     */
    public static List<Integer> rankLines(List<Boolean> direct, List<Double> score) {
        List<Integer> idx = new ArrayList<>();
        for (int i = 0; i < direct.size(); i++) idx.add(i);
        final List<Boolean> d = direct;
        final List<Double> s = score;
        Collections.sort(idx, new Comparator<Integer>() {
            @Override
            public int compare(Integer a, Integer b) {
                boolean da = Boolean.TRUE.equals(d.get(a));
                boolean db = Boolean.TRUE.equals(d.get(b));
                if (da != db) return da ? -1 : 1;
                int sa = bucket(s.get(a) == null ? 0 : s.get(a));
                int sb = bucket(s.get(b) == null ? 0 : s.get(b));
                if (sa != sb) return Integer.compare(sb, sa);
                return Integer.compare(a, b);
            }
        });
        return idx;
    }

    private static final Pattern NUMBER = Pattern.compile("\\d+");

    /**
     * 换线路或换站点后，在新的剧集列表里找到“同一集”。
     * 依次按：集名完全一致 → 集名中的数字一致 → 原下标（集数相同或新列表足够长）。找不到返回 -1。
     */
    public static int alignEpisode(List<String> names, String currentName, int currentIndex, int currentTotal) {
        if (names == null || names.isEmpty()) return -1;
        String cur = normalize(currentName);
        if (!cur.isEmpty()) {
            for (int i = 0; i < names.size(); i++) {
                if (cur.equals(normalize(names.get(i)))) return i;
            }
        }
        String num = firstNumber(currentName);
        if (num != null && currentTotal > 1) {
            int target = Integer.parseInt(num);
            for (int i = 0; i < names.size(); i++) {
                String n = firstNumber(names.get(i));
                if (n != null && Integer.parseInt(n) == target) return i;
            }
        }
        if (currentTotal <= 1 && names.size() >= 1) return 0;
        if (currentIndex >= 0 && currentIndex < names.size()) return currentIndex;
        return -1;
    }

    private static String firstNumber(String s) {
        if (s == null) return null;
        Matcher m = NUMBER.matcher(s);
        if (!m.find()) return null;
        String g = m.group();
        // 避免把年份、分辨率当集数
        return g.length() > 4 ? null : g;
    }
}
