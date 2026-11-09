package com.github.tvbox.osc.ui.tv;

import android.graphics.Bitmap;

import androidx.annotation.NonNull;

import com.bumptech.glide.load.engine.bitmap_recycle.BitmapPool;
import com.bumptech.glide.load.resource.bitmap.BitmapTransformation;

import java.nio.charset.Charset;
import java.security.MessageDigest;

/**
 * Glide 变换：先把图缩到 sampleWidth 宽，再做盒式模糊。
 * 用于首页 / 详情页的氛围背景：图越小越省内存，放大显示时本来就是一片柔和的色块。
 */
public class BlurTransformation extends BitmapTransformation {

    private static final String ID = "com.github.tvbox.osc.ui.tv.BlurTransformation.v1";
    private static final byte[] ID_BYTES = ID.getBytes(Charset.forName("UTF-8"));

    private final int sampleWidth;
    private final int radius;

    public BlurTransformation(int sampleWidth, int radius) {
        this.sampleWidth = Math.max(8, sampleWidth);
        this.radius = Math.max(1, radius);
    }

    @Override
    protected Bitmap transform(@NonNull BitmapPool pool, @NonNull Bitmap toTransform, int outWidth, int outHeight) {
        int srcW = toTransform.getWidth();
        int srcH = toTransform.getHeight();
        if (srcW <= 0 || srcH <= 0) {
            return toTransform;
        }
        int w = Math.min(sampleWidth, srcW);
        int h = Math.max(1, Math.round(srcH * (w / (float) srcW)));
        Bitmap out = pool.get(w, h, Bitmap.Config.ARGB_8888);
        android.graphics.Canvas canvas = new android.graphics.Canvas(out);
        android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG);
        canvas.drawBitmap(toTransform, null, new android.graphics.Rect(0, 0, w, h), paint);
        canvas.setBitmap(null);
        int[] px = new int[w * h];
        out.getPixels(px, 0, w, 0, 0, w, h);
        BoxBlur.blur(px, w, h, radius);
        out.setPixels(px, 0, w, 0, 0, w, h);
        return out;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof BlurTransformation)) {
            return false;
        }
        BlurTransformation other = (BlurTransformation) o;
        return other.sampleWidth == sampleWidth && other.radius == radius;
    }

    @Override
    public int hashCode() {
        return ID.hashCode() * 31 * 31 + sampleWidth * 31 + radius;
    }

    @Override
    public void updateDiskCacheKey(@NonNull MessageDigest messageDigest) {
        messageDigest.update(ID_BYTES);
        messageDigest.update((byte) sampleWidth);
        messageDigest.update((byte) (sampleWidth >> 8));
        messageDigest.update((byte) radius);
    }
}
