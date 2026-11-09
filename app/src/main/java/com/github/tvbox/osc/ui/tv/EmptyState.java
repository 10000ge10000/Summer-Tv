package com.github.tvbox.osc.ui.tv;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.StringRes;

import com.github.tvbox.osc.R;

/** 列表空状态视图：配合 BaseQuickAdapter.setEmptyView 使用，数据为空时自动显示 */
public final class EmptyState {
    private EmptyState() {
    }

    public static View create(ViewGroup list, @StringRes int title, @StringRes int hint) {
        View view = LayoutInflater.from(list.getContext()).inflate(R.layout.layout_empty_state, list, false);
        ((TextView) view.findViewById(R.id.tvEmptyTitle)).setText(title);
        ((TextView) view.findViewById(R.id.tvEmptyHint)).setText(hint);
        return view;
    }
}
