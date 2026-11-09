package com.github.tvbox.osc.ui.tv;

import android.view.View;
import android.view.animation.Interpolator;

import androidx.core.view.animation.PathInterpolatorCompat;

/**
 * Summer TV 统一动效参数（UI 重构阶段 A）。
 * <p>
 * 数值来自定稿样稿的「动效时长表」。页面里不要再各自写 scale / duration / interpolator，
 * 统一调用这里的方法，后续调整节奏只改这一处。
 * <p>
 * 只用 scale / alpha / translation 这类 GPU 友好的属性，不用弹跳插值器：
 * 低端电视盒子上弹跳既掉帧又显得廉价，放大 1.2 倍还会挤到相邻卡片。
 */
public final class Motion {

    /** 焦点框滑动、卡片放大、行内滚动 */
    public static final long DURATION_FOCUS = 300L;
    /** 整页上下滚动 */
    public static final long DURATION_SCROLL = 520L;
    /** 非焦点行压暗 / 恢复 */
    public static final long DURATION_DIM = 320L;
    /** 背景氛围图切换（交叉淡入） */
    public static final long DURATION_AMBIENT = 600L;
    /** 焦点停留多久才切换 Hero 文案和背景，避免快速划过时闪烁 */
    public static final long DELAY_SETTLE = 300L;
    /** 共享元素：海报飞入详情 / 飞回 */
    public static final long DURATION_SHARED_ENTER = 560L;
    public static final long DURATION_SHARED_RETURN = 520L;
    /** 播放层圆形展开 / 收回 */
    public static final long DURATION_REVEAL = 620L;
    /** 依次入场（波浪）的单步间隔 */
    public static final long STAGGER_STEP = 24L;

    /** 海报 / 卡片获得焦点时的放大倍数 */
    public static final float SCALE_CARD = 1.08f;
    /** 胶囊按钮、快捷入口获得焦点时的放大倍数 */
    public static final float SCALE_BUTTON = 1.06f;
    /** 首页分类文字获得焦点时的放大倍数（阶段 B 改为胶囊指示器后不再使用） */
    public static final float SCALE_TAB = 1.1f;
    /** 非焦点行压暗后的透明度 */
    public static final float DIM_ALPHA = 0.45f;

    private static Interpolator sEase;
    private static Interpolator sEaseScroll;

    private Motion() {
    }

    /** 焦点、按钮、转场通用缓动：cubic-bezier(0.2, 0.8, 0.2, 1)，快出慢停 */
    public static Interpolator ease() {
        if (sEase == null) {
            sEase = PathInterpolatorCompat.create(0.2f, 0.8f, 0.2f, 1f);
        }
        return sEase;
    }

    /** 整页滚动缓动：cubic-bezier(0.22, 1, 0.36, 1)，减速尾巴更长 */
    public static Interpolator easeScroll() {
        if (sEaseScroll == null) {
            sEaseScroll = PathInterpolatorCompat.create(0.22f, 1f, 0.36f, 1f);
        }
        return sEaseScroll;
    }

    /** 以统一时长和缓动把 view 缩放到 scale；重复调用会接着当前进度继续，不会跳变 */
    public static void focus(View view, float scale) {
        if (view == null) {
            return;
        }
        view.animate()
                .scaleX(scale)
                .scaleY(scale)
                .setDuration(DURATION_FOCUS)
                .setInterpolator(ease())
                .start();
    }

    /** 海报 / 卡片获得焦点 */
    public static void focusCard(View view) {
        focus(view, SCALE_CARD);
    }

    /** 按钮 / 快捷入口获得焦点 */
    public static void focusButton(View view) {
        focus(view, SCALE_BUTTON);
    }

    /** 失去焦点，恢复原始大小 */
    public static void unfocus(View view) {
        focus(view, 1f);
    }
}
