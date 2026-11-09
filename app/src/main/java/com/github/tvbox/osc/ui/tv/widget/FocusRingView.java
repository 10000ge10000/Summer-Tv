package com.github.tvbox.osc.ui.tv.widget;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewParent;
import android.view.ViewTreeObserver;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.ui.tv.Motion;

/**
 * 浮动焦点框（UI 重构阶段 B）：整页只有一个描边框，焦点换卡片时从旧位置滑到新位置。
 *
 * 框跟踪的是目标 View 本身而不是一次性坐标：每帧绘制前重新换算目标在窗口中的位置
 * （包含父容器滚动和卡片放大动画），所以列表滚动、卡片缩放过程中框都贴合卡片。
 */
public class FocusRingView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF from = new RectF();
    private final RectF to = new RectF();
    private final RectF drawn = new RectF();
    private final RectF measured = new RectF();
    private float targetScale = 1f;
    private final int[] selfLocation = new int[2];
    private final float baseRadius;
    private final float gap;
    private View target;
    private float progress = 1f;
    private boolean showing = false;
    private ValueAnimator slide;

    // 每帧绘制前检查目标位置，只有真的变了（滚动、缩放、布局）才重绘，静止时不产生额外帧
    private final ViewTreeObserver.OnPreDrawListener tracker = () -> {
        if (showing && updateTarget()) {
            invalidate();
        }
        return true;
    };

    public FocusRingView(Context context) {
        this(context, null);
    }

    public FocusRingView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setWillNotDraw(false);
        setFocusable(false);
        setClickable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(getResources().getDimension(R.dimen.st_focus_stroke));
        baseRadius = getResources().getDimension(R.dimen.st_radius_card);
        gap = getResources().getDimension(R.dimen.st_focus_ring_gap);
        setAlpha(0f);
    }

    public void setColor(int color) {
        paint.setColor(color);
        invalidate();
    }

    /** 移动到新的目标；当前不可见时直接出现在目标处，可见时滑过去 */
    public void moveTo(View newTarget) {
        if (newTarget == null) {
            hide();
            return;
        }
        boolean slideFromCurrent = showing && target != null && progress > 0f && !drawn.isEmpty();
        if (slideFromCurrent) {
            from.set(drawn);
        }
        target = newTarget;
        if (slideFromCurrent) {
            startSlide();
        } else {
            progress = 1f;
            cancelSlide();
        }
        updateTarget();
        if (!showing) {
            showing = true;
            animate().cancel();
            animate().alpha(1f).setDuration(Motion.DURATION_FOCUS / 2).setInterpolator(Motion.ease()).start();
        }
        invalidate();
    }

    public void hide() {
        if (!showing) {
            return;
        }
        showing = false;
        target = null;
        cancelSlide();
        animate().cancel();
        animate().alpha(0f).setDuration(Motion.DURATION_FOCUS / 2).setInterpolator(Motion.ease()).start();
    }

    private void startSlide() {
        cancelSlide();
        progress = 0f;
        slide = ValueAnimator.ofFloat(0f, 1f);
        slide.setDuration(Motion.DURATION_FOCUS);
        slide.setInterpolator(Motion.ease());
        slide.addUpdateListener(a -> {
            progress = (float) a.getAnimatedValue();
            invalidate();
        });
        slide.start();
    }

    private void cancelSlide() {
        if (slide != null) {
            slide.cancel();
            slide = null;
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        getViewTreeObserver().addOnPreDrawListener(tracker);
    }

    @Override
    protected void onDetachedFromWindow() {
        getViewTreeObserver().removeOnPreDrawListener(tracker);
        cancelSlide();
        super.onDetachedFromWindow();
    }

    /** 重新计算目标在本 View 坐标系中的外框；返回是否有变化。目标失效时收起焦点框 */
    private boolean updateTarget() {
        if (target == null || !target.isAttachedToWindow() || !target.isShown()) {
            hide();
            return false;
        }
        float scale = mapToWindow(target, measured);
        getLocationInWindow(selfLocation);
        measured.offset(-selfLocation[0], -selfLocation[1]);
        measured.inset(-gap * scale, -gap * scale);
        if (measured.equals(to) && scale == targetScale) {
            return false;
        }
        to.set(measured);
        targetScale = scale;
        return true;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (to.isEmpty() || (!showing && getAlpha() == 0f)) {
            return;
        }
        if (progress >= 1f || from.isEmpty()) {
            drawn.set(to);
        } else {
            drawn.set(lerp(from.left, to.left), lerp(from.top, to.top),
                    lerp(from.right, to.right), lerp(from.bottom, to.bottom));
        }
        float radius = (baseRadius + gap) * targetScale;
        canvas.drawRoundRect(drawn, radius, radius, paint);
    }

    private float lerp(float a, float b) {
        return a + (b - a) * progress;
    }

    /**
     * 把 view 的边界换算到窗口坐标，逐级应用每个祖先的变换矩阵（缩放、位移）和滚动偏移。
     * 返回累计的横向缩放，用于同步放大圆角和间距。
     */
    private static float mapToWindow(View view, RectF out) {
        out.set(0, 0, view.getWidth(), view.getHeight());
        float scale = 1f;
        View current = view;
        while (true) {
            Matrix matrix = current.getMatrix();
            if (!matrix.isIdentity()) {
                matrix.mapRect(out);
                scale *= current.getScaleX();
            }
            out.offset(current.getLeft(), current.getTop());
            ViewParent parent = current.getParent();
            if (!(parent instanceof View)) {
                break;
            }
            View parentView = (View) parent;
            out.offset(-parentView.getScrollX(), -parentView.getScrollY());
            current = parentView;
        }
        return scale;
    }
}
