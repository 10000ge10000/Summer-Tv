package com.github.tvbox.osc.util;

import com.github.tvbox.osc.bean.LiveChannelGroup;
import com.github.tvbox.osc.bean.LiveChannelItem;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import com.orhanobut.hawk.Hawk;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 按频道记忆直播线路，以及每条线路在本机的起播耗时。
 *
 * <p>v1 按「频道名 → 线路下标」记忆：线路合并或新增后下标会错位，记住的线路变成另一条。
 * v2 改为「频道键 → 线路 URL」，并附带「URL → 最近一次起播耗时」，供线路排序使用。
 *
 * <p>Hawk 中的 JSON 只在首次使用时反序列化一次，之后读写内存；修改后标记为脏，
 * 由 {@link #flush()}（直播页 onStop / onDestroy 调用）统一写回。
 */
public final class LiveSourceMemory {

    static final String KEY = "live_line_memory_v2";
    /** v1：{频道名: 线路下标} */
    static final String LEGACY_KEY = "live_channel_source_index";
    static final int MAX_CHANNELS = 1000;
    static final int MAX_LATENCY = 3000;

    private static final Gson GSON = new Gson();
    private static final Type LEGACY_TYPE = new TypeToken<Map<String, Integer>>() {}.getType();

    private static State state;
    private static boolean dirty;

    private LiveSourceMemory() {
    }

    /** 内存中的记忆数据。preferred：频道键 → 首选 URL；latency：URL → 起播耗时（毫秒） */
    static final class State {
        final LinkedHashMap<String, String> preferred = lru(MAX_CHANNELS);
        final LinkedHashMap<String, Long> latency = lru(MAX_LATENCY);
    }

    private static <V> LinkedHashMap<String, V> lru(final int max) {
        return new LinkedHashMap<String, V>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, V> eldest) {
                return size() > max;
            }
        };
    }

    private static synchronized State state() {
        if (state == null) {
            String json = null;
            try {
                json = Hawk.get(KEY, "");
            } catch (Throwable th) {
                LOG.e("Read live line memory failed: " + th.getMessage());
            }
            state = parse(json);
        }
        return state;
    }

    /** @return 该频道上次播放成功或手动选择的线路 URL，没有记录时为 null */
    public static synchronized String preferredUrl(String channelKey) {
        if (isEmpty(channelKey)) return null;
        return state().preferred.get(channelKey);
    }

    public static synchronized void rememberUrl(String channelKey, String url) {
        if (isEmpty(channelKey) || isEmpty(url)) return;
        String old = state().preferred.put(channelKey, url);
        if (!url.equals(old)) dirty = true;
    }

    /** @return 该线路在本机最近一次的起播耗时，未知时为 -1 */
    public static synchronized long latency(String url) {
        if (isEmpty(url)) return -1;
        Long value = state().latency.get(url);
        return value == null ? -1 : value;
    }

    public static synchronized void recordLatency(String url, long millis) {
        if (isEmpty(url) || millis < 0) return;
        state().latency.put(url, millis);
        dirty = true;
    }

    /** 写回 Hawk；没有改动时不写 */
    public static synchronized void flush() {
        if (!dirty || state == null) return;
        try {
            Hawk.put(KEY, toJson(state));
            dirty = false;
        } catch (Throwable th) {
            LOG.e("Save live line memory failed: " + th.getMessage());
        }
    }

    /**
     * 把 v1 的「频道名 → 下标」换算成 v2 的「频道键 → URL」。
     *
     * <p>必须在线路排序之前调用：v1 的下标对应的是按来源顺序合并的原始线路列表。
     * 换算完成后删除 v1 数据；没有 v1 数据时直接返回。
     */
    public static void migrateLegacy(List<LiveChannelGroup> groups) {
        Map<String, Integer> legacy;
        try {
            if (!Hawk.contains(LEGACY_KEY)) return;
            String json = Hawk.get(LEGACY_KEY, "");
            legacy = isEmpty(json) ? null : GSON.<Map<String, Integer>>fromJson(json, LEGACY_TYPE);
        } catch (Throwable th) {
            legacy = null;
        }
        if (legacy != null && !legacy.isEmpty() && groups != null) {
            synchronized (LiveSourceMemory.class) {
                State s = state();
                for (LiveChannelGroup group : groups) {
                    if (group == null || group.getLiveChannels() == null) continue;
                    for (LiveChannelItem item : group.getLiveChannels()) {
                        String key = item.getChannelKey();
                        if (isEmpty(key) || s.preferred.containsKey(key)) continue;
                        String url = legacyIndexToUrl(legacy, namesOf(item), item.getChannelUrls());
                        if (url != null) {
                            s.preferred.put(key, url);
                            dirty = true;
                        }
                    }
                }
            }
            flush();
        }
        try {
            Hawk.delete(LEGACY_KEY);
            // 旧的「全局线路下标」在各频道之间没有对应关系，不再使用
            Hawk.delete(HawkConfig.LIVE_SOURCE_INDEX);
        } catch (Throwable ignored) {
        }
    }

    private static List<String> namesOf(LiveChannelItem item) {
        List<String> names = new ArrayList<>();
        if (item.getChannelName() != null) names.add(item.getChannelName());
        if (item.getAliases() != null) names.addAll(item.getAliases());
        return names;
    }

    /** 把首选线路挪到 sourceIndex：频道进入时直接播放记住的线路 */
    public static void applyTo(List<LiveChannelGroup> groups) {
        if (groups == null) return;
        for (LiveChannelGroup group : groups) {
            if (group == null || group.getLiveChannels() == null) continue;
            for (LiveChannelItem item : group.getLiveChannels()) {
                item.setSourceIndex(indexOfPreferred(item.getChannelUrls(), preferredUrl(item.getChannelKey())));
            }
        }
    }

    // ---------------- 以下为纯函数，便于单测 ----------------

    static State parse(String json) {
        State s = new State();
        if (isEmpty(json)) return s;
        try {
            JsonObject root = GSON.fromJson(json, JsonObject.class);
            if (root == null) return s;
            if (root.has("preferred") && root.get("preferred").isJsonObject()) {
                for (Map.Entry<String, com.google.gson.JsonElement> e : root.getAsJsonObject("preferred").entrySet()) {
                    if (e.getValue().isJsonPrimitive()) s.preferred.put(e.getKey(), e.getValue().getAsString());
                }
            }
            if (root.has("latency") && root.get("latency").isJsonObject()) {
                for (Map.Entry<String, com.google.gson.JsonElement> e : root.getAsJsonObject("latency").entrySet()) {
                    if (e.getValue().isJsonPrimitive()) s.latency.put(e.getKey(), e.getValue().getAsLong());
                }
            }
        } catch (Throwable ignored) {
            // 数据损坏时当作没有记忆，不影响直播
        }
        return s;
    }

    static String toJson(State s) {
        JsonObject root = new JsonObject();
        JsonObject preferred = new JsonObject();
        for (Map.Entry<String, String> e : s.preferred.entrySet()) preferred.addProperty(e.getKey(), e.getValue());
        JsonObject latency = new JsonObject();
        for (Map.Entry<String, Long> e : s.latency.entrySet()) latency.addProperty(e.getKey(), e.getValue());
        root.add("preferred", preferred);
        root.add("latency", latency);
        return root.toString();
    }

    /** 旧下标越界或没有记录时返回 null */
    static String legacyIndexToUrl(Map<String, Integer> legacy, List<String> names, List<String> urls) {
        if (legacy == null || names == null || urls == null || urls.isEmpty()) return null;
        for (String name : names) {
            Integer index = name == null ? null : legacy.get(name);
            if (index == null) continue;
            if (index >= 0 && index < urls.size()) return urls.get(index);
            return null;
        }
        return null;
    }

    static int indexOfPreferred(List<String> urls, String preferred) {
        if (urls == null || preferred == null) return 0;
        int index = urls.indexOf(preferred);
        return Math.max(index, 0);
    }

    /** 单测用：注入状态，避免访问 Hawk */
    static synchronized void resetForTest(State s) {
        state = s;
        dirty = false;
    }

    private static boolean isEmpty(String value) {
        return value == null || value.isEmpty();
    }
}
