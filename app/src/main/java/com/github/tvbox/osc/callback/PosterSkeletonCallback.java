package com.github.tvbox.osc.callback;

import android.content.Context;
import android.view.View;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.ui.tv.Skeleton;
import com.kingja.loadsir.callback.Callback;

/** 海报网格的加载态：骨架屏（UI 重构阶段 B），替代转圈 */
public class PosterSkeletonCallback extends Callback {
    @Override
    protected int onCreateView() {
        return R.layout.loadsir_poster_skeleton;
    }

    @Override
    protected void onViewCreate(Context context, View view) {
        super.onViewCreate(context, view);
        Skeleton.pulse(view);
    }
}
