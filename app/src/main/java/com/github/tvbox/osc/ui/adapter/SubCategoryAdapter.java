package com.github.tvbox.osc.ui.adapter;

import android.graphics.Color;
import android.widget.TextView;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.bean.MovieSort;

import java.util.ArrayList;

/** Android TV 分类页顶部的真实子分类选择器。 */
public class SubCategoryAdapter extends BaseQuickAdapter<MovieSort.SortData, BaseViewHolder> {
    private String selectedId;

    public SubCategoryAdapter() {
        super(R.layout.item_sub_category, new ArrayList<>());
    }

    public void setSelectedId(String selectedId) {
        if (this.selectedId == null ? selectedId == null : this.selectedId.equals(selectedId)) {
            return;
        }
        this.selectedId = selectedId;
        notifyDataSetChanged();
    }

    @Override
    protected void convert(BaseViewHolder helper, MovieSort.SortData item) {
        boolean selected = item != null && item.id != null && item.id.equals(selectedId);
        String name = (item == null || item.name == null) ? "" : item.name;
        helper.setText(R.id.subCategoryTitle, name);
        helper.itemView.setActivated(selected);
        TextView title = helper.getView(R.id.subCategoryTitle);
        title.setTextColor(selected ? Color.WHITE : 0xB3FFFFFF);
        title.getPaint().setFakeBoldText(selected);
    }
}
