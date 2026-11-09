package com.github.tvbox.osc.ui.tv.widget;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.FrameLayout;

/**
 * 按背景形状（圆角）裁切子 View 的 FrameLayout。
 * 海报卡片用它保证封面图、角标、删除遮罩都落在同一个圆角里，与焦点描边对齐。
 * XML 的 android:clipToOutline 属性要 API 31 才有，这里在构造时开启，兼容 API 21。
 */
public class RoundClipFrameLayout extends FrameLayout {

    public RoundClipFrameLayout(Context context) {
        this(context, null);
    }

    public RoundClipFrameLayout(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public RoundClipFrameLayout(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        setClipToOutline(true);
    }
}
