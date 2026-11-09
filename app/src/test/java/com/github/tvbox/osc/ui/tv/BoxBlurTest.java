package com.github.tvbox.osc.ui.tv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class BoxBlurTest {

    private static int argb(int a, int r, int g, int b) {
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    @Test
    public void uniformImageStaysUniform() {
        int c = argb(255, 200, 100, 50);
        int[] px = new int[12 * 8];
        java.util.Arrays.fill(px, c);
        BoxBlur.blur(px, 12, 8, 3);
        for (int p : px) {
            assertEquals(c, p);
        }
    }

    @Test
    public void brightPixelSpreadsToNeighbours() {
        int w = 15, h = 15;
        int[] px = new int[w * h];
        java.util.Arrays.fill(px, argb(255, 0, 0, 0));
        px[7 * w + 7] = argb(255, 255, 255, 255);
        BoxBlur.blur(px, w, h, 2);
        int center = (px[7 * w + 7] >> 16) & 0xFF;
        int near = (px[7 * w + 8] >> 16) & 0xFF;
        int corner = (px[0] >> 16) & 0xFF;
        assertTrue("中心应被摊薄", center < 255);
        assertTrue("相邻像素应变亮", near > 0);
        assertTrue("中心不暗于相邻", center >= near);
        assertEquals("远处角落不受影响", 0, corner);
        assertEquals("alpha 保持不透明", 255, px[7 * w + 8] >>> 24);
    }

    @Test
    public void edgesAreClampedNotWrapped() {
        // 左半白、右半黑：模糊后最左列仍然是白，不会从右边“卷”过来变暗
        int w = 10, h = 4;
        int[] px = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                px[y * w + x] = x < 5 ? argb(255, 255, 255, 255) : argb(255, 0, 0, 0);
            }
        }
        BoxBlur.blur(px, w, h, 1);
        assertEquals(255, (px[0] >> 16) & 0xFF);
        assertEquals(0, (px[w - 1] >> 16) & 0xFF);
    }

    @Test
    public void invalidArgumentsAreIgnored() {
        int[] px = {argb(255, 1, 2, 3)};
        BoxBlur.blur(px, 1, 1, 0);
        BoxBlur.blur(null, 1, 1, 2);
        assertEquals(argb(255, 1, 2, 3), px[0]);
    }

    @Test(expected = IllegalArgumentException.class)
    public void mismatchedLengthIsRejected() {
        BoxBlur.blur(new int[5], 2, 3, 1);
    }
}
