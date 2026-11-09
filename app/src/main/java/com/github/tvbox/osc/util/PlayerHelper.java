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
        int playerType = Hawk.get(HawkConfig.PLAY_TYPE, 0);
        int renderType = Hawk.get(HawkConfig.PLAY_RENDER, 0);
        String ijkCode = Hawk.get(HawkConfig.IJK_CODEC, "软解码");
        int scale = Hawk.get(HawkConfig.PLAY_SCALE, 0);
        try {
            playerType = playerCfg.getInt("pl");
            //就我遇到的问题是 Exo 在 TextureView 黑屏 调整设置中的渲染模式无法生效
            //renderType = playerCfg.getInt("pr");//该值无法修改，一旦确认该值后续无法进行修改 就是在设置选的 类型无法应用
            ijkCode = playerCfg.getString("ijk");
            scale = playerCfg.getInt("sc");
        } catch (JSONException e) {
            e.printStackTrace();
        }
        if (forcePlayerType >= 0) playerType = forcePlayerType;
        playerType = availablePlayerType(playerType);
        IJKCode codec = ApiConfig.get().getIJKCodec(ijkCode);
        PlayerFactory playerFactory;
        if (playerType == 1) {
            playerFactory = new PlayerFactory<IjkmPlayer>() {
                @Override
                public IjkmPlayer createPlayer(Context context) {
                    return new IjkmPlayer(context, codec);
                }
            };
        } else if (playerType == 2) {
            playerFactory = new PlayerFactory<EXOmPlayer>() {
                @Override
                public EXOmPlayer createPlayer(Context context) {
                    EXOmPlayer player = new EXOmPlayer(context);
                    player.setFastStart(vodFastStart);
                    return player;
                }
            };
        } else {
            playerFactory = AndroidMediaPlayerFactory.create();
        }
        RenderViewFactory renderViewFactory = null;
        if (playerType==2){
            renderViewFactory = PlayerViewRenderViewFactory.create(renderType);
        }else{
            switch (renderType) {
                case 0:
                default:
                    renderViewFactory = TextureRenderViewFactory.create();
                    break;
                case 1:
                    renderViewFactory = SurfaceRenderViewFactory.create();
                    break;
            }
        }

        videoView.setPlayerFactory(playerFactory);
        videoView.setRenderViewFactory(renderViewFactory);
        videoView.setScreenScaleType(scale);
    }

    public static void updateCfg(VideoView videoView) {
        int playType = availablePlayerType(Hawk.get(HawkConfig.PLAY_TYPE, 0));
        PlayerFactory playerFactory;
        if (playType == 1) {
            playerFactory = new PlayerFactory<IjkmPlayer>() {
                @Override
                public IjkmPlayer createPlayer(Context context) {
                    return new IjkmPlayer(context, null);
                }
            };

        } else if (playType == 2) {
            playerFactory = new PlayerFactory<EXOmPlayer>() {
                @Override
                public EXOmPlayer createPlayer(Context context) {
                    return new EXOmPlayer(context);
                }
            };
        } else {
            playerFactory = AndroidMediaPlayerFactory.create();
        }
        int renderType = Hawk.get(HawkConfig.PLAY_RENDER, 0);
        RenderViewFactory renderViewFactory = null;
        switch (renderType) {
            case 0:
            default:
                renderViewFactory = TextureRenderViewFactory.create();
                break;
            case 1:
                renderViewFactory = SurfaceRenderViewFactory.create();
                break;
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
    private static int availablePlayerType(int playerType) {
        if (playerType == 1 || playerType == 3) {
            LOG.i("Player type " + playerType + " has no native library in this build, fallback to Exo");
            return PLAYER_TYPE_EXO;
        }
        return playerType;
    }

    public static void init() {
        // native 库按需打包，此处无需预加载；见 app/build.gradle 的 packagingOptions
    }

    public static String getPlayerName(int playType) {
        if (playType == 1) {
            return "IJK";
        } else if (playType == 2) {
            return "Exo";
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
