package com.github.tvbox.osc.live;

import com.github.tvbox.osc.bean.LiveChannelGroup;
import com.github.tvbox.osc.bean.LiveChannelItem;
import com.github.tvbox.osc.bean.LivePlayerManager;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.LOG;
import com.orhanobut.hawk.Hawk;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 直播模块的数据迁移。
 *
 * <p>{@link #run()} 在 App 启动时调用（Hawk 初始化之后），幂等，带版本标记；
 * {@link #migrateChannelScales(List)} 需要频道列表，由 {@link LiveRepository} 在列表加载后调用。
 */
public final class LiveMigration {

    static final String VERSION_KEY = "live_migration_version";
    static final int CURRENT_VERSION = 1;

    private LiveMigration() {
    }

    public static void run() {
        try {
            int version = Hawk.get(VERSION_KEY, 0);
            if (version >= CURRENT_VERSION) return;
            if (version < 1) migrateV1();
            Hawk.put(VERSION_KEY, CURRENT_VERSION);
        } catch (Throwable th) {
            LOG.e("Live migration failed: " + th.getMessage());
        }
    }

    private static void migrateV1() {
        // 1) 超时换源：旧选项 关/5/10/15/20/25/30 秒 → 新选项 关/5/10/15/20 秒
        if (Hawk.contains(HawkConfig.LIVE_CONNECT_TIMEOUT)) {
            int old = Hawk.get(HawkConfig.LIVE_CONNECT_TIMEOUT, LiveChannelSwitcher.TIMEOUT_DEFAULT_INDEX);
            Hawk.put(HawkConfig.LIVE_CONNECT_TIMEOUT, mapLegacyTimeout(old));
        }
        // 2) 直播解码：IJK 未打包，存的 IJK(1) / 阿里(3) 映射为 Exo(2)
        if (Hawk.contains(HawkConfig.LIVE_PLAYER_TYPE)) {
            int old = Hawk.get(HawkConfig.LIVE_PLAYER_TYPE, LivePlayerManager.PLAYER_SYSTEM);
            Hawk.put(HawkConfig.LIVE_PLAYER_TYPE, LivePlayerManager.sanitizePlayerType(old));
        }
        // 3) 直播源固化为内置源：清掉用户自定义的直播地址和历史
        Hawk.delete(HawkConfig.LIVE_URL);
        Hawk.delete(HawkConfig.LIVE_HISTORY);
    }

    /** 旧下标 0..6 → 新下标 0..4；25s / 30s 归到 20s，非法值回到默认 10s */
    static int mapLegacyTimeout(int oldIndex) {
        if (oldIndex < 0 || oldIndex > 6) return LiveChannelSwitcher.TIMEOUT_DEFAULT_INDEX;
        return Math.min(oldIndex, LiveChannelSwitcher.TIMEOUT_SECONDS.length - 1);
    }

    /**
     * 把旧版「以频道名为 Hawk 键」的播放器配置迁移到画面比例 Map。
     *
     * <p>Hawk 不能枚举键，只能拿当前列表里每个频道的名字和别名（合并前的原始名）逐个尝试。
     * 只处理形如 {"pl":..,"sc":..} 的值，避免误删同名的其他配置。可重复执行。
     */
    public static void migrateChannelScales(List<LiveChannelGroup> groups) {
        if (groups == null) return;
        int defaultScale = Hawk.get(HawkConfig.PLAY_SCALE, 0);
        int migrated = 0;
        for (LiveChannelGroup group : groups) {
            if (group == null || group.getLiveChannels() == null) continue;
            for (LiveChannelItem item : group.getLiveChannels()) {
                for (String name : legacyNames(item)) {
                    try {
                        if (!Hawk.contains(name)) continue;
                        int scale = readLegacyScale(Hawk.get(name));
                        if (scale == Integer.MIN_VALUE) continue;
                        if (!LivePlayerManager.hasScaleFor(item.getChannelKey())) {
                            LivePlayerManager.putScale(item.getChannelKey(), scale, defaultScale);
                        }
                        Hawk.delete(name);
                        migrated++;
                    } catch (Throwable th) {
                        LOG.e("Migrate live scale failed: " + th.getMessage());
                    }
                }
            }
        }
        if (migrated > 0) LOG.i("Live scale migrated: " + migrated);
    }

    private static Set<String> legacyNames(LiveChannelItem item) {
        Set<String> names = new LinkedHashSet<>();
        if (item.getChannelName() != null) names.add(item.getChannelName());
        ArrayList<String> aliases = item.getAliases();
        if (aliases != null) names.addAll(aliases);
        names.remove("");
        return names;
    }

    /** @return 旧配置中的画面比例；不是旧播放器配置时返回 Integer.MIN_VALUE */
    static int readLegacyScale(Object value) {
        try {
            JSONObject obj;
            if (value instanceof JSONObject) {
                obj = (JSONObject) value;
            } else if (value instanceof Map) {
                obj = new JSONObject((Map<?, ?>) value);
            } else if (value instanceof String) {
                obj = new JSONObject((String) value);
            } else {
                return Integer.MIN_VALUE;
            }
            if (!obj.has("sc") || !obj.has("pl")) return Integer.MIN_VALUE;
            return obj.getInt("sc");
        } catch (Throwable th) {
            return Integer.MIN_VALUE;
        }
    }
}
