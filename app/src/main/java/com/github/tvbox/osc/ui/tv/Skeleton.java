package com.github.tvbox.osc.ui.tv;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.view.View;

import com.github.tvbox.osc.R;

/** 骨架屏呼吸动画：View 显示在窗口上时循环淡入淡出，离开窗口自动停止 */
public final class Skeleton {
    private static final long DURATION_PULSE = 900L;
    private static final float ALPHA_LOW = 0.4f;

    private Skeleton() {
    }

    public static void pulse(View view) {
        if (view.getTag(R.id.mItemFrame) instanceof ObjectAnimator) {
            return; // 已经挂过，避免重复监听
        }
        ObjectAnimator animator = ObjectAnimator.ofFloat(view, View.ALPHA, 1f, ALPHA_LOW);
        animator.setDuration(DURATION_PULSE);
        animator.setInterpolator(Motion.ease());
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setRepeatMode(ValueAnimator.REVERSE);
        view.setTag(R.id.mItemFrame, animator);
        view.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View v) {
                animator.start();
            }

            @Override
            public void onViewDetachedFromWindow(View v) {
                animator.cancel();
                v.setAlpha(1f);
            }
        });
        if (view.isAttachedToWindow()) {
            animator.start();
        }
    }
}
