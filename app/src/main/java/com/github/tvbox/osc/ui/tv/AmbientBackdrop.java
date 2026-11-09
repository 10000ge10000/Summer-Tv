package com.github.tvbox.osc.ui.tv;

import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.bumptech.glide.Glide;
import com.bumptech.glide.request.target.CustomTarget;
import com.bumptech.glide.request.transition.Transition;
import com.github.tvbox.osc.util.ImgUtil;

/**
 * 氛围背景：两层 ImageView 交替显示当前焦点海报的模糊图，交叉淡入。
 * <p>
 * 焦点停留 {@link Motion#DELAY_SETTLE} 后才加载，快速划过时不会反复切图闪烁；
 * 每层复用同一个 Glide Target，新请求会自动取消该层上一个未完成的请求。
 */
public final class AmbientBackdrop {

    /** 解码尺寸：只取一张小图，模糊后放大到全屏 */
    private static final int DECODE_W = 160;
    private static final int DECODE_H = 240;
    private static final BlurTransformation BLUR = new BlurTransformation(48, 4);

    private final ImageView[] layers;
    private final LayerTarget[] targets;
    private final float visibleAlpha;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private int front = 0;
    private String shownUrl;
    private String pendingUrl;

    private final Runnable loadPending = new Runnable() {
        @Override
        public void run() {
            load();
        }
    };

    public AmbientBackdrop(ImageView first, ImageView second, float visibleAlpha) {
        this.layers = new ImageView[]{first, second};
        this.targets = new LayerTarget[]{new LayerTarget(0), new LayerTarget(1)};
        this.visibleAlpha = visibleAlpha;
        first.setAlpha(0f);
        second.setAlpha(0f);
    }

    /** 请求切换到 url 对应的海报；url 为空或与当前相同时忽略 */
    public void show(@Nullable String url) {
        if (TextUtils.isEmpty(url)) {
            return;
        }
        pendingUrl = url;
        handler.removeCallbacks(loadPending);
        if (url.equals(shownUrl)) {
            return;
        }
        handler.postDelayed(loadPending, Motion.DELAY_SETTLE);
    }

    /** 页面销毁时调用，取消未执行的切换 */
    public void release() {
        handler.removeCallbacks(loadPending);
        // 在途的 Glide 请求随宿主生命周期清理；这里再丢弃结果，避免销毁后仍触发淡入动画
        pendingUrl = null;
    }

    private void load() {
        String url = pendingUrl;
        if (TextUtils.isEmpty(url) || url.equals(shownUrl)) {
            return;
        }
        int back = front ^ 1;
        ImageView view = layers[back];
        LayerTarget target = targets[back];
        target.url = url;
        // 海报地址可能带 @Referer= 等请求头后缀，必须和卡片走同一套解析，否则豆瓣图床会拒绝（418）
        Object model = ImgUtil.getUrl(url);
        if (model == null) {
            return;
        }
        try {
            Glide.with(view)
                    .asBitmap()
                    .load(model)
                    .disallowHardwareConfig()
                    .transform(BLUR)
                    .into(target);
        } catch (IllegalArgumentException ignored) {
            // 宿主 Activity 已销毁时 Glide 会抛出该异常，背景切换可以安全放弃
        }
    }

    private void onLayerReady(int index, String url, Bitmap bitmap) {
        if (pendingUrl == null || !url.equals(pendingUrl)) {
            return; // 期间焦点又换了，丢弃过期结果，等最新请求
        }
        ImageView in = layers[index];
        ImageView out = layers[index ^ 1];
        in.setImageBitmap(bitmap);
        in.animate().cancel();
        out.animate().cancel();
        in.animate().alpha(visibleAlpha).setDuration(Motion.DURATION_AMBIENT).setInterpolator(Motion.ease()).start();
        out.animate().alpha(0f).setDuration(Motion.DURATION_AMBIENT).setInterpolator(Motion.ease()).start();
        front = index;
        shownUrl = url;
    }

    private final class LayerTarget extends CustomTarget<Bitmap> {
        private final int index;
        String url;

        LayerTarget(int index) {
            super(DECODE_W, DECODE_H);
            this.index = index;
        }

        @Override
        public void onResourceReady(@NonNull Bitmap resource, @Nullable Transition<? super Bitmap> transition) {
            if (url != null) {
                onLayerReady(index, url, resource);
            }
        }

        @Override
        public void onLoadCleared(@Nullable Drawable placeholder) {
            // Glide 回收该层的位图前必须解除引用，否则会绘制已回收的 Bitmap
            layers[index].setImageDrawable(null);
            if (index == front) {
                shownUrl = null;
            }
        }
    }
}
