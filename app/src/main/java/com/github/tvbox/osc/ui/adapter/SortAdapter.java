package com.github.tvbox.osc.ui.adapter;

import androidx.recyclerview.widget.RecyclerView;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.bean.MovieSort;

import java.util.ArrayList;

/**
 * @author pj567
 * @date :2020/12/21
 * @description:
 */
public class SortAdapter extends BaseQuickAdapter<MovieSort.SortData, BaseViewHolder> {
    /** 当前显示的分类，对应胶囊 Tab 的 activated 状态 */
    private int activePosition = 0;

    public SortAdapter() {
        super(R.layout.item_home_sort, new ArrayList<>());
    }

    @Override
    protected void convert(BaseViewHolder helper, MovieSort.SortData item) {
        helper.setText(R.id.tvTitle, item.name);
        helper.itemView.setActivated(helper.getAdapterPosition() == activePosition);
    }

    /** 切换当前分类；直接更新可见条目的状态，不走 notify，避免 Tab 焦点被重新绑定打断 */
    public void setActivePosition(RecyclerView list, int position) {
        activePosition = position;
        if (list == null) {
            return;
        }
        for (int i = 0; i < list.getChildCount(); i++) {
            android.view.View child = list.getChildAt(i);
            child.setActivated(list.getChildAdapterPosition(child) == position);
        }
    }
}