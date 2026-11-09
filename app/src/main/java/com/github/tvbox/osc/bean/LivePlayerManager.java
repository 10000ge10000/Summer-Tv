package com.github.tvbox.osc.bean;

import androidx.annotation.NonNull;

import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.LOG;
import com.github.tvbox.osc.util.PlayerHelper;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.orhanobut.hawk.Hawk;

import org.json.JSONException;
import org.json.JSONObject;

import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;

import xyz.doikki.videoplayer.player.VideoView;

/**
 * 直播播放器配置：解码器全局生效（系统 / Exo），画面比例按频道记忆。
 *
 * <p>旧实现直接用「频道名」作 Hawk 键存整份播放器配置：Hawk 无法枚举这些键，
 * 频道改名后数据就成了孤儿。现在统一存到一个 JSON Map（频道键 → 画面比例），
 * 旧数据由 {@code LiveMigration.migrateChannelScales} 在频道列表加载后逐个迁移。
 */
public class LivePlayerManager {
    /** 直播解码器取值（与 PlayerHelper 一致）：0 系统，2 Exo。IJK 未打包，不再提供 */
    public static final int PLAYER_SYSTEM = 0;
    public static final int PLAYER_EXO = 2;
    /** 设置菜单中的解码下标 */
    public static final int DECODER_INDEX_SYSTEM = 0;
    public static final int DECODER_INDEX_EXO = 1;

    public static final String SCALE_MAP_KEY = "live_channel_scale_map";
    private static final Type SCALE_MAP_TYPE = new TypeToken<HashMap<String, Integer>>() {}.getType();
    private static Map<String, Integer> scaleMap;

    private final JSONObject defaultPlayerConfig = new JSONObject();
    private JSONObject currentPlayerConfig;

    /** 把存储值规整为可用的解码器：IJK(1)/阿里(3) 映射为 Exo，其余未知值回到系统 */
    public static int sanitizePlayerType(int playerType) {
        if (playerType == 1 || playerType == 3 || playerType == PLAYER_EXO) return PLAYER_EXO;
        return PLAYER_SYSTEM;
    }

    public void init(VideoView videoView) {
        try {
            int playerType = sanitizePlayerType(Hawk.get(HawkConfig.LIVE_PLAYER_TYPE, PLAYER_SYSTEM));
            defaultPlayerConfig.put("pl", playerType);
            defaultPlayerConfig.put("ijk", "软解码");
            defaultPlayerConfig.put("pr", Hawk.get(HawkConfig.PLAY_RENDER, 0));
            defaultPlayerConfig.put("sc", Hawk.get(HawkConfig.PLAY_SCALE, 0));
        } catch (JSONException e) {
            e.printStackTrace();
        }
        getDefaultLiveChannelPlayer(videoView);
    }

    public void getDefaultLiveChannelPlayer(VideoView videoView) {
        PlayerHelper.updateCfg(videoView, defaultPlayerConfig);
        try {
            currentPlayerConfig = new JSONObject(defaultPlayerConfig.toString());
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    /** 播放某个频道前调用：应用全局解码器和该频道记住的画面比例 */
    public void getLiveChannelPlayer(VideoView videoView, String channelKey) {
        if (currentPlayerConfig == null) {
            getDefaultLiveChannelPlayer(videoView);
        }
        try {
            int scale = getScaleFor(channelKey, defaultPlayerConfig.getInt("sc"));
            if (currentPlayerConfig.getInt("pl") != defaultPlayerConfig.getInt("pl")) {
                JSONObject cfg = new JSONObject(defaultPlayerConfig.toString());
                cfg.put("sc", scale);
                PlayerHelper.updateCfg(videoView, cfg);
                currentPlayerConfig = cfg;
            } else if (currentPlayerConfig.getInt("sc") != scale) {
                videoView.setScreenScaleType(scale);
                currentPlayerConfig.put("sc", scale);
            }
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    /** @return 设置菜单中的解码下标 */
    public int getLivePlayerType() {
        try {
            return currentPlayerConfig.getInt("pl") == PLAYER_EXO ? DECODER_INDEX_EXO : DECODER_INDEX_SYSTEM;
        } catch (Throwable e) {
            return DECODER_INDEX_SYSTEM;
        }
    }

    public int getLivePlayerScale() {
        try {
            return currentPlayerConfig.getInt("sc");
        } catch (Throwable e) {
            return 0;
        }
    }

    /** @param decoderIndex 设置菜单中的解码下标 */
    public void changeLivePlayerType(VideoView videoView, int decoderIndex) {
        int playerType = decoderIndex == DECODER_INDEX_EXO ? PLAYER_EXO : PLAYER_SYSTEM;
        Hawk.put(HawkConfig.LIVE_PLAYER_TYPE, playerType);
        try {
            defaultPlayerConfig.put("pl", playerType);
            JSONObject cfg = new JSONObject(currentPlayerConfig.toString());
            cfg.put("pl", playerType);
            PlayerHelper.updateCfg(videoView, cfg);
            currentPlayerConfig = cfg;
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    public void changeLivePlayerScale(@NonNull VideoView videoView, int playerScale, String channelKey) {
        videoView.setScreenScaleType(playerScale);
        try {
            currentPlayerConfig.put("sc", playerScale);
            putScale(channelKey, playerScale, defaultPlayerConfig.getInt("sc"));
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    // ---------------- 画面比例 Map ----------------

    private static synchronized Map<String, Integer> scales() {
        if (scaleMap == null) {
            Map<String, Integer> map = null;
            try {
                String json = Hawk.get(SCALE_MAP_KEY, "");
                if (json != null && !json.isEmpty()) map = new Gson().fromJson(json, SCALE_MAP_TYPE);
            } catch (Throwable th) {
                LOG.e("Read live scale map failed: " + th.getMessage());
            }
            scaleMap = map == null ? new HashMap<String, Integer>() : map;
        }
        return scaleMap;
    }

    public static synchronized int getScaleFor(String channelKey, int fallback) {
        if (channelKey == null) return fallback;
        Integer value = scales().get(channelKey);
        return value == null ? fallback : value;
    }

    public static synchronized boolean hasScaleFor(String channelKey) {
        return channelKey != null && scales().containsKey(channelKey);
    }

    /** 与默认比例相同时删除记录，不占空间 */
    public static synchronized void putScale(String channelKey, int scale, int defaultScale) {
        if (channelKey == null || channelKey.isEmpty()) return;
        Map<String, Integer> map = scales();
        if (scale == defaultScale) {
            if (map.remove(channelKey) == null) return;
        } else {
            Integer old = map.put(channelKey, scale);
            if (old != null && old == scale) return;
        }
        try {
            Hawk.put(SCALE_MAP_KEY, new Gson().toJson(map));
        } catch (Throwable th) {
            LOG.e("Save live scale map failed: " + th.getMessage());
        }
    }
}
