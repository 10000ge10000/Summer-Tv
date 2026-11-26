package com.github.tvbox.osc.util;

import android.content.Context;

import com.blankj.utilcode.util.ToastUtils;
import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.bean.IJKCode;
import com.github.tvbox.osc.player.EXOmPlayer;
import com.github.tvbox.osc.player.IjkmPlayer;
import com.github.tvbox.osc.player.render.SurfaceRenderViewFactory;
import com.orhanobut.hawk.Hawk;

import org.json.JSONException;
import org.json.JSONObject;

import tv.danmaku.ijk.media.player.IjkMediaPlayer;
import xyz.doikki.videoplayer.player.AndroidMediaPlayerFactory;
import xyz.doikki.videoplayer.player.PlayerFactory;
import xyz.doikki.videoplayer.player.VideoView;
import xyz.doikki.videoplayer.render.PlayerViewRenderViewFactory;
import xyz.doikki.videoplayer.render.RenderViewFactory;
import xyz.doikki.videoplayer.render.TextureRenderViewFactory;

public class PlayerHelper {
    public static void updateCfg(VideoView videoView, JSONObject playerCfg) {
        updateCfg(videoView, playerCfg, -1);
    }

    public static void updateCfg(VideoView videoView, JSONObject playerCfg, int forcePlayerType) {
        updateCfg(videoView, playerCfg, forcePlayerType, false);
    }

    /** @param vodFastStart 点播传 true：Exo 缓冲 1 秒即开播；直播走上面的重载，保持默认缓冲 */
    public static void updateCfg(VideoView videoView, JSONObject playerCfg, int forcePlayerType, boolean vodFastStart) {
        int playerType = Hawk.get(HawkConfig.PLAY_TYPE, PlaybackCompatProfile.PLAYER_EXO_HW);
        int renderType = Hawk.get(HawkConfig.PLAY_RENDER, PlaybackCompatProfile.RENDER_SURFACE);
        String ijkCode = Hawk.get(HawkConfig.IJK_CODEC, "软解码");
        int scale = Hawk.get(HawkConfig.PLAY_SCALE, 0);
        if (playerCfg != null) {
            playerType = playerCfg.optInt("pl", playerType);
            renderType = playerCfg.optInt("pr", renderType);
            ijkCode = playerCfg.optString("ijk", ijkCode);
            scale = playerCfg.optInt("sc", scale);
        }
        if (forcePlayerType >= 0) playerType = forcePlayerType;
        playerType = availablePlayerType(playerType);
        renderType = PlaybackCompatProfile.normalizeRenderType(renderType);
        IJKCode codec = ApiConfig.get().getIJKCodec(ijkCode);
        PlayerFactory playerFactory;
        if (playerType == 1) {
            playerFactory = new PlayerFactory<IjkmPlayer>() {
                @Override
                public IjkmPlayer createPlayer(Context context) {
                    return new IjkmPlayer(context, codec);
                }
            };
        } else if (playerType == PlaybackCompatProfile.PLAYER_EXO_HW || playerType == PlaybackCompatProfile.PLAYER_EXO_SW) {
            final boolean preferSw = (playerType == PlaybackCompatProfile.PLAYER_EXO_SW);
            playerFactory = new PlayerFactory<EXOmPlayer>() {
                @Override
                public EXOmPlayer createPlayer(Context context) {
                    EXOmPlayer player = new EXOmPlayer(context);
                    player.setFastStart(vodFastStart);
                    player.setPreferSoftwareDecoder(preferSw);
                    return player;
                }
            };
        } else {
            playerFactory = AndroidMediaPlayerFactory.create();
        }
        RenderViewFactory renderViewFactory;
        if (playerType == PlaybackCompatProfile.PLAYER_EXO_HW || playerType == PlaybackCompatProfile.PLAYER_EXO_SW) {
            renderViewFactory = PlayerViewRenderViewFactory.create(renderType);
        } else {
            renderViewFactory = (renderType == PlaybackCompatProfile.RENDER_SURFACE)
                    ? SurfaceRenderViewFactory.create()
                    : TextureRenderViewFactory.create();
        }

        videoView.setPlayerFactory(playerFactory);
        videoView.setRenderViewFactory(renderViewFactory);
        videoView.setScreenScaleType(scale);
    }

    public static void updateCfg(VideoView videoView) {
        int playType = availablePlayerType(Hawk.get(HawkConfig.PLAY_TYPE, PlaybackCompatProfile.PLAYER_EXO_HW));
        int renderType = PlaybackCompatProfile.normalizeRenderType(Hawk.get(HawkConfig.PLAY_RENDER, PlaybackCompatProfile.RENDER_SURFACE));
        PlayerFactory playerFactory;
        if (playType == PlaybackCompatProfile.PLAYER_EXO_HW || playType == PlaybackCompatProfile.PLAYER_EXO_SW) {
            final boolean preferSw = (playType == PlaybackCompatProfile.PLAYER_EXO_SW);
            playerFactory = new PlayerFactory<EXOmPlayer>() {
                @Override
                public EXOmPlayer createPlayer(Context context) {
                    EXOmPlayer player = new EXOmPlayer(context);
                    player.setPreferSoftwareDecoder(preferSw);
                    return player;
                }
            };
        } else {
            playerFactory = AndroidMediaPlayerFactory.create();
        }
        RenderViewFactory renderViewFactory;
        if (playType == PlaybackCompatProfile.PLAYER_EXO_HW || playType == PlaybackCompatProfile.PLAYER_EXO_SW) {
            renderViewFactory = PlayerViewRenderViewFactory.create(renderType);
        } else {
            renderViewFactory = (renderType == PlaybackCompatProfile.RENDER_SURFACE)
                    ? SurfaceRenderViewFactory.create()
                    : TextureRenderViewFactory.create();
        }
        videoView.setPlayerFactory(playerFactory);
        videoView.setRenderViewFactory(renderViewFactory);
    }

    /** Exo，IJK 与阿里云的 native 库未打包时的统一回退目标 */
    private static final int PLAYER_TYPE_EXO = 2;

    /**
     * IJK(1) 与阿里云(3) 的 native 库未打进 APK，直接使用会 UnsatisfiedLinkError。
     *
     * <p>兜底放在这里而不是只改设置项：每条视频记录里保存的 playerCfg 也能指定
     * 播放器（{@code playerCfg.getInt("pl")}），老记录会把已移除的类型带回来，
     * 只迁移设置值挡不住。
     */
    public static int availablePlayerType(int playerType) {
        int normalized = PlaybackCompatProfile.normalizePlayerType(playerType);
        if (normalized != playerType) {
            LOG.i("Player type " + playerType + " unavailable in this build, fallback to " + normalized);
        }
        return normalized;
    }

    public static void init() {
        // native 库按需打包，此处无需预加载；见 app/build.gradle 的 packagingOptions
    }

    public static String getPlayerName(int playType) {
        if (playType == 1) {
            return "IJK";
        } else if (playType == 2) {
            return "Exo";
        } else if (playType == 4) {
            return "Exo软解";
        } else if (playType == 3) {
            return "阿里";
        } else if (playType == 10) {
            return "MX";
        } else if (playType == 11) {
            return "Reex";
        } else if (playType == 12) {
            return "Kodi";
        } else {
            return "系统";
        }
    }

    public static String getRenderName(int renderType) {
        if (renderType == 1) {
            return "SurfaceView";
        } else {
            return "TextureView";
        }
    }

    public static String getScaleName(int screenScaleType) {
        String scaleText = "默认";
        switch (screenScaleType) {
            case VideoView.SCREEN_SCALE_DEFAULT:
                scaleText = "默认";
                break;
            case VideoView.SCREEN_SCALE_16_9:
                scaleText = "16:9";
                break;
            case VideoView.SCREEN_SCALE_4_3:
                scaleText = "4:3";
                break;
            case VideoView.SCREEN_SCALE_MATCH_PARENT:
                scaleText = "填充";
                break;
            case VideoView.SCREEN_SCALE_ORIGINAL:
                scaleText = "原始";
                break;
            case VideoView.SCREEN_SCALE_CENTER_CROP:
                scaleText = "裁剪";
                break;
        }
        return scaleText;
    }

    public static String getRootCauseMessage(Throwable th) {
        for (int i=0; i<10; i++) {
            if (th.getCause() == null) return th.getLocalizedMessage();
            else th = th.getCause();
        }
        return th.getLocalizedMessage();
    }

}
