package com.github.tvbox.osc.ui.tv;

/**
 * 纯 Java 的盒式模糊（三遍近似高斯），只依赖 int[] 像素数组，便于在 JVM 单元测试里验证。
 * <p>
 * 用在已经缩到很小（宽 64 像素左右）的海报图上，单次耗时在亚毫秒级；
 * 不用 RenderScript（已废弃），也不用 RenderEffect（需要 API 31，本项目 minSdk 为 21）。
 */
public final class BoxBlur {

    private static final int PASSES = 3;

    private BoxBlur() {
    }

    /**
     * 原地模糊 ARGB 像素。
     *
     * @param pixels 行优先排列的 ARGB 像素，长度必须等于 width * height
     * @param radius 单遍半径，小于 1 时不处理
     */
    public static void blur(int[] pixels, int width, int height, int radius) {
        if (pixels == null || width <= 0 || height <= 0 || radius < 1) {
            return;
        }
        if (pixels.length != width * height) {
            throw new IllegalArgumentException("pixels.length != width * height");
        }
        int[] tmp = new int[pixels.length];
        for (int pass = 0; pass < PASSES; pass++) {
            horizontal(pixels, tmp, width, height, radius);
            vertical(tmp, pixels, width, height, radius);
        }
    }

    private static void horizontal(int[] src, int[] dst, int w, int h, int r) {
        int div = r * 2 + 1;
        for (int y = 0; y < h; y++) {
            int row = y * w;
            int sa = 0, sr = 0, sg = 0, sb = 0;
            for (int i = -r; i <= r; i++) {
                int c = src[row + clamp(i, w)];
                sa += c >>> 24;
                sr += (c >> 16) & 0xFF;
                sg += (c >> 8) & 0xFF;
                sb += c & 0xFF;
            }
            for (int x = 0; x < w; x++) {
                dst[row + x] = ((sa / div) << 24) | ((sr / div) << 16) | ((sg / div) << 8) | (sb / div);
                int add = src[row + clamp(x + r + 1, w)];
                int rem = src[row + clamp(x - r, w)];
                sa += (add >>> 24) - (rem >>> 24);
                sr += ((add >> 16) & 0xFF) - ((rem >> 16) & 0xFF);
                sg += ((add >> 8) & 0xFF) - ((rem >> 8) & 0xFF);
                sb += (add & 0xFF) - (rem & 0xFF);
            }
        }
    }

    private static void vertical(int[] src, int[] dst, int w, int h, int r) {
        int div = r * 2 + 1;
        for (int x = 0; x < w; x++) {
            int sa = 0, sr = 0, sg = 0, sb = 0;
            for (int i = -r; i <= r; i++) {
                int c = src[clamp(i, h) * w + x];
                sa += c >>> 24;
                sr += (c >> 16) & 0xFF;
                sg += (c >> 8) & 0xFF;
                sb += c & 0xFF;
            }
            for (int y = 0; y < h; y++) {
                dst[y * w + x] = ((sa / div) << 24) | ((sr / div) << 16) | ((sg / div) << 8) | (sb / div);
                int add = src[clamp(y + r + 1, h) * w + x];
                int rem = src[clamp(y - r, h) * w + x];
                sa += (add >>> 24) - (rem >>> 24);
                sr += ((add >> 16) & 0xFF) - ((rem >> 16) & 0xFF);
                sg += ((add >> 8) & 0xFF) - ((rem >> 8) & 0xFF);
                sb += (add & 0xFF) - (rem & 0xFF);
            }
        }
    }

    private static int clamp(int i, int size) {
        return i < 0 ? 0 : (i >= size ? size - 1 : i);
    }
}
