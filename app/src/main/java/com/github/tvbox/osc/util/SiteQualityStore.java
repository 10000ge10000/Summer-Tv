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
 * 本机站点 / 线路播放质量统计，只保存在本机 Hawk（键 site_quality），不上传。
 *
 * <p>键：站点用 sourceKey，线路用 sourceKey + "|" + 线路名。
 * 每次记录先按半衰期衰减旧计数，所以很久以前的成败影响越来越小。
 */
public final class SiteQualityStore {

    private static final String KEY = "site_quality";
    private static final int MAX_ENTRIES = 400;

    private static final class Stat {
        double s;   // 成功次数（已衰减）
        double f;   // 失败次数（已衰减）
        double ms;  // 起播耗时的指数平均
        long t;     // 最近一次记录时间
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
        double k = SourceRanker.decay(System.currentTimeMillis() - st.t);
        return SourceRanker.qualityScore(st.s * k, st.f * k, st.ms);
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
        st.s += 1;
        if (firstFrameMs > 0) {
            st.ms = st.ms <= 0 ? firstFrameMs : st.ms * 0.7 + firstFrameMs * 0.3;
        }
        save();
    }

    public static synchronized void recordFailure(String key) {
        if (key == null) return;
        Stat st = touch(key);
        st.f += 1;
        save();
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
