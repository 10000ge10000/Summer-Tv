package com.github.tvbox.osc.util;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.orhanobut.hawk.Hawk;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 本机站点 / 线路播放质量统计与短期熔断记录，只保存在本机 Hawk（键 site_quality），不上传。
 *
 * <p>键：站点用 sourceKey，线路用 sourceKey + "|" + 线路名。
 * 每次记录先按半衰期衰减旧计数；连续失败达到阈值时进入短期熔断冷却，避免反复点击死源。
 */
public final class SiteQualityStore {

    private static final String KEY = "site_quality";
    private static final int MAX_ENTRIES = 400;
    /** 连续失败达到此次数后触发短期熔断 */
    public static final int CIRCUIT_FAIL_THRESHOLD = 3;
    /** 单次熔断冷却时长：6 小时 */
    public static final long CIRCUIT_COOLDOWN_MS = 6L * 60 * 60 * 1000;

    static final class Stat {
        double s;      // 成功次数（已衰减）
        double f;      // 失败次数（已衰减）
        double ms;     // 起播/探测耗时的指数平均
        long t;        // 最近一次记录时间
        int cf;        // 连续失败次数
        long coolUntil; // 短期熔断截止时间戳（毫秒）
    }

    private static Map<String, Stat> cache;

    private SiteQualityStore() {
    }

    public static String lineKey(String sourceKey, String flag) {
        return sourceKey + "|" + flag;
    }

    public static synchronized double score(String key) {
        Stat st = load().get(key);
        if (st == null) return SourceRanker.unknownScore();
        long now = System.currentTimeMillis();
        double k = SourceRanker.decay(now - st.t);
        double base = SourceRanker.qualityScore(st.s * k, st.f * k, st.ms);
        // 处于短期熔断中的站点额外降权，确保即使回退“全部源”也沉在最底
        return isCircuitOpen(st, now) ? base * 0.25 : base;
    }

    /** 站点或线路当前是否处于短期熔断冷却期内 */
    public static synchronized boolean isCircuitOpen(String key) {
        if (key == null) return false;
        Stat st = load().get(key);
        return isCircuitOpen(st, System.currentTimeMillis());
    }

    static boolean isCircuitOpen(Stat st, long nowMs) {
        return st != null && st.coolUntil > nowMs;
    }

    public static SourceRanker.Quality quality() {
        return new SourceRanker.Quality() {
            @Override
            public double score(String key) {
                return SiteQualityStore.score(key);
            }
        };
    }

    public static synchronized void recordSuccess(String key, long firstFrameMs) {
        if (key == null) return;
        Stat st = touch(key);
        applySuccess(st, 1.0, firstFrameMs);
        save();
    }

    public static synchronized void recordFailure(String key) {
        if (key == null) return;
        Stat st = touch(key);
        applyFailure(st, 1.0, System.currentTimeMillis());
        save();
    }

    /** 主动探测成功：清除熔断并轻量加分 */
    public static synchronized void recordProbeSuccess(String key, long latencyMs) {
        if (key == null) return;
        Stat st = touch(key);
        applySuccess(st, 0.5, latencyMs);
        save();
    }

    /** 主动探测失败：推进连续失败计数，达到阈值触发短期熔断 */
    public static synchronized void recordProbeFailure(String key) {
        if (key == null) return;
        Stat st = touch(key);
        applyFailure(st, 0.5, System.currentTimeMillis());
        save();
    }

    static void applySuccess(Stat st, double weight, long latencyMs) {
        st.s += weight;
        st.cf = 0;
        st.coolUntil = 0;
        if (latencyMs > 0) {
            st.ms = st.ms <= 0 ? latencyMs : st.ms * 0.7 + latencyMs * 0.3;
        }
    }

    static void applyFailure(Stat st, double weight, long nowMs) {
        st.f += weight;
        st.cf += 1;
        if (st.cf >= CIRCUIT_FAIL_THRESHOLD) {
            st.coolUntil = nowMs + CIRCUIT_COOLDOWN_MS;
        }
    }

    private static Stat touch(String key) {
        Map<String, Stat> map = load();
        Stat st = map.get(key);
        long now = System.currentTimeMillis();
        if (st == null) {
            st = new Stat();
            map.put(key, st);
        } else {
            double k = SourceRanker.decay(now - st.t);
            st.s *= k;
            st.f *= k;
        }
        st.t = now;
        return st;
    }

    private static Map<String, Stat> load() {
        if (cache != null) return cache;
        Map<String, Stat> map = null;
        try {
            String json = Hawk.get(KEY, "");
            if (json != null && !json.isEmpty()) {
                map = new Gson().fromJson(json, new TypeToken<HashMap<String, Stat>>() {
                }.getType());
            }
        } catch (Throwable ignored) {
            // 数据损坏时从头统计
        }
        cache = map == null ? new HashMap<String, Stat>() : map;
        return cache;
    }

    private static void save() {
        Map<String, Stat> map = load();
        if (map.size() > MAX_ENTRIES) {
            List<Map.Entry<String, Stat>> entries = new ArrayList<>(map.entrySet());
            Collections.sort(entries, new Comparator<Map.Entry<String, Stat>>() {
                @Override
                public int compare(Map.Entry<String, Stat> a, Map.Entry<String, Stat> b) {
                    return Long.compare(a.getValue().t, b.getValue().t);
                }
            });
            for (int i = 0; i < map.size() - MAX_ENTRIES && i < entries.size(); i++) {
                map.remove(entries.get(i).getKey());
            }
        }
        try {
            Hawk.put(KEY, new Gson().toJson(map));
        } catch (Throwable ignored) {
        }
    }
}
