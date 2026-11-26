package com.github.tvbox.osc.util;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 播放器兼容档位与老旧设备自动降级决策（纯逻辑，不依赖 Android）。
 *
 * <p>老旧电视/盒子常见问题：
 * <ul>
 *   <li>TextureView 绑定硬解输出黑屏 → 默认改用 SurfaceView</li>
 *   <li>Exo 硬解不支持特定 Profile/H.265/音轨 → 启用 NextLib FFmpeg 软解回退与软解档</li>
 *   <li>极老芯片仅厂商原厂 MediaPlayer 集成了闭源硬解 → 兜底切到系统播放器</li>
 * </ul>
 */
public final class PlaybackCompatProfile {

    /** 系统原生 AndroidMediaPlayer */
    public static final int PLAYER_SYSTEM = 0;
    /** Media3 ExoPlayer（硬解优先，硬解失败自动回退系统/FFmpeg 软解） */
    public static final int PLAYER_EXO_HW = 2;
    /** Media3 ExoPlayer（FFmpeg 扩展软解优先，用于规避老芯片硬解花屏/黑屏） */
    public static final int PLAYER_EXO_SW = 4;

    /** TextureView 渲染 */
    public static final int RENDER_TEXTURE = 0;
    /** SurfaceView 渲染（Android TV 硬解直出推荐默认） */
    public static final int RENDER_SURFACE = 1;

    public static final class Mode {
        public final int playerType;
        public final int renderType;

        public Mode(int playerType, int renderType) {
            this.playerType = playerType;
            this.renderType = renderType;
        }

        public String key() {
            return modeKey(playerType, renderType);
        }

        public String displayLabel() {
            return label(playerType, renderType);
        }
    }

    /** 自动降级阶梯顺序 */
    private static final List<Mode> FALLBACK_LADDER = Arrays.asList(
            new Mode(PLAYER_EXO_HW, RENDER_SURFACE),
            new Mode(PLAYER_EXO_HW, RENDER_TEXTURE),
            new Mode(PLAYER_EXO_SW, RENDER_SURFACE),
            new Mode(PLAYER_SYSTEM, RENDER_SURFACE)
    );

    private PlaybackCompatProfile() {
    }

    public static String modeKey(int playerType, int renderType) {
        return normalizePlayerType(playerType) + ":" + normalizeRenderType(renderType);
    }

    /** 将历史失效值（IJK=1、阿里=3）和未知值收敛为当前实际可用内核（外部播放器 10/11/12 保留） */
    public static int normalizePlayerType(int playerType) {
        if (playerType >= 10 && playerType <= 12) return playerType;
        if (playerType == PLAYER_SYSTEM || playerType == PLAYER_EXO_HW || playerType == PLAYER_EXO_SW) {
            return playerType;
        }
        return PLAYER_EXO_HW;
    }

    public static int normalizeRenderType(int renderType) {
        return renderType == RENDER_TEXTURE ? RENDER_TEXTURE : RENDER_SURFACE;
    }

    /** 是否属于内置播放器（非外部调用的 MX / Reex / Kodi） */
    public static boolean isBuiltInPlayer(int playerType) {
        return playerType < 10;
    }

    /**
     * 判断 Media3 PlaybackException 错误码或异常消息是否属于解码/渲染/音轨兼容问题。
     *
     * <p>网络 IO / HTTP 错误码在 2000-2999 段，属于片源问题，不应触发内核降级。
     */
    public static boolean isCompatibilityError(int errorCode, String errorMessage) {
        // Media3 Parsing(3001-3004), Decoder(4001-4005), AudioTrack(5001-5002)
        if ((errorCode >= 3001 && errorCode <= 3004)
                || (errorCode >= 4001 && errorCode <= 4999)
                || (errorCode >= 5001 && errorCode <= 5999)) {
            return true;
        }
        if (errorCode >= 2000 && errorCode <= 2999) {
            return false;
        }
        if (errorMessage == null || errorMessage.isEmpty()) {
            return false;
        }
        String lower = errorMessage.toLowerCase(Locale.ROOT);
        return lower.contains("mediacodec")
                || lower.contains("decoder")
                || lower.contains("decoding")
                || lower.contains("surface")
                || lower.contains("audiotrack")
                || lower.contains("codec")
                || lower.contains("unrecognizedinputformatexception")
                || lower.contains("exceeds_capabilities");
    }

    /**
     * 从降级阶梯中选取下一个尚未尝试过的兼容模式。
     *
     * @param currentPlayer 当前播放器类型
     * @param currentRender 当前渲染类型
     * @param triedKeys     本次播放已尝试过的模式 key 集合
     * @param allowSystem   是否允许降级到系统播放器（DASH/MPD 流应传 false）
     * @return 下一个要尝试的 Mode；若所有可用档位均已试过则返回 null
     */
    public static Mode nextFallback(int currentPlayer, int currentRender, Set<String> triedKeys, boolean allowSystem) {
        if (!isBuiltInPlayer(currentPlayer)) {
            return null;
        }
        String curKey = modeKey(currentPlayer, currentRender);
        for (Mode candidate : FALLBACK_LADDER) {
            if (!allowSystem && candidate.playerType == PLAYER_SYSTEM) {
                continue;
            }
            String key = candidate.key();
            if (key.equals(curKey)) {
                continue;
            }
            if (triedKeys != null && triedKeys.contains(key)) {
                continue;
            }
            return candidate;
        }
        return null;
    }

    public static String label(int playerType, int renderType) {
        int pt = normalizePlayerType(playerType);
        int rt = normalizeRenderType(renderType);
        String pName;
        if (pt == PLAYER_SYSTEM) {
            pName = "系统播放器";
        } else if (pt == PLAYER_EXO_SW) {
            pName = "Exo软解";
        } else {
            pName = "Exo";
        }
        String rName = rt == RENDER_SURFACE ? "Surface" : "Texture";
        return pName + "·" + rName;
    }
}
