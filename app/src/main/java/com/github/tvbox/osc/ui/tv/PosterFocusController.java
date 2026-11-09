package com.github.tvbox.osc.ui.tv;

import android.view.View;
import android.view.ViewParent;
import android.view.ViewTreeObserver;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.ui.tv.widget.FocusRingView;

/**
 * 首页海报焦点表现（UI 重构阶段 B）：
 * 1. 浮动焦点框：焦点在海报卡片之间移动时，同一个描边框滑过去；
 * 2. 行压暗：海报网格中不在焦点所在行的卡片降低透明度，突出当前行。
 *
 * 通过全局焦点监听统一处理，不需要每个 Fragment 自己调用；
 * 只处理 scope（首页 ViewPager）内、带 R.id.mItemFrame 海报框的列表条目。
 */
public class PosterFocusController implements ViewTreeObserver.OnGlobalFocusChangeListener {
    private final View scope;
    private final FocusRingView ring;
    private RecyclerView dimmedList;
    private View focusedCard;

    private final RecyclerView.OnChildAttachStateChangeListener attachListener =
            new RecyclerView.OnChildAttachStateChangeListener() {
                @Override
                public void onChildViewAttachedToWindow(@NonNull View child) {
                    // 刚加入的条目还没完成布局，等布局后再按行决定明暗
                    child.post(() -> applyDim(child, false));
                }

                @Override
                public void onChildViewDetachedFromWindow(@NonNull View child) {
                    child.animate().cancel();
                    child.setAlpha(1f);
                }
            };

    public PosterFocusController(View scope, FocusRingView ring) {
        this.scope = scope;
        this.ring = ring;
    }

    public void attach() {
        scope.getViewTreeObserver().addOnGlobalFocusChangeListener(this);
    }

    public void release() {
        if (scope.getViewTreeObserver().isAlive()) {
            scope.getViewTreeObserver().removeOnGlobalFocusChangeListener(this);
        }
        clearDim();
    }

    @Override
    public void onGlobalFocusChanged(View oldFocus, View newFocus) {
        View frame = cardFrameOf(newFocus);
        if (frame == null) {
            ring.hide();
            clearDim();
            return;
        }
        useStaticStroke(frame);
        ring.moveTo(frame);
        dimRows((RecyclerView) newFocus.getParent(), newFocus);
    }

    /** 焦点 View 是 scope 内某个列表的海报条目时，返回它的海报框，否则返回 null */
    private View cardFrameOf(View focused) {
        if (focused == null || !(focused.getParent() instanceof RecyclerView) || !isInScope(focused)) {
            return null;
        }
        return focused.findViewById(R.id.mItemFrame);
    }

    private boolean isInScope(View view) {
        ViewParent parent = view.getParent();
        while (parent instanceof View) {
            if (parent == scope) {
                return true;
            }
            parent = parent.getParent();
        }
        return false;
    }

    /** 首页卡片的焦点描边由浮动框负责，卡片自身换成只有细描边的前景，避免出现两层框 */
    private void useStaticStroke(View frame) {
        if (frame.getTag(R.id.mItemFrame) != null) {
            return;
        }
        frame.setTag(R.id.mItemFrame, Boolean.TRUE);
        frame.setForeground(ContextCompat.getDrawable(frame.getContext(), R.drawable.st_card_stroke));
    }

    private void dimRows(RecyclerView list, View card) {
        if (list != dimmedList) {
            clearDim();
            dimmedList = list;
            list.removeOnChildAttachStateChangeListener(attachListener);
            list.addOnChildAttachStateChangeListener(attachListener);
        }
        focusedCard = card;
        for (int i = 0; i < list.getChildCount(); i++) {
            applyDim(list.getChildAt(i), true);
        }
    }

    private void applyDim(View child, boolean animate) {
        if (dimmedList == null || focusedCard == null || child.getParent() != dimmedList
                || child.findViewById(R.id.mItemFrame) == null) {
            return;
        }
        // 按顶部坐标判断是否同一行；列表滚动时所有条目一起移动，用焦点卡片的实时位置比较
        boolean sameRow = Math.abs(child.getTop() - focusedCard.getTop()) < child.getHeight() / 2;
        float alpha = sameRow ? 1f : Motion.DIM_ALPHA;
        if (!animate) {
            child.animate().cancel();
            child.setAlpha(alpha);
        } else if (child.getAlpha() != alpha) {
            child.animate().alpha(alpha).setDuration(Motion.DURATION_DIM).setInterpolator(Motion.ease()).start();
        }
    }

    private void clearDim() {
        RecyclerView list = dimmedList;
        dimmedList = null;
        focusedCard = null;
        if (list == null) {
            return;
        }
        list.removeOnChildAttachStateChangeListener(attachListener);
        for (int i = 0; i < list.getChildCount(); i++) {
            View child = list.getChildAt(i);
            if (child.getAlpha() != 1f) {
                child.animate().alpha(1f).setDuration(Motion.DURATION_DIM).setInterpolator(Motion.ease()).start();
            }
        }
    }
}
