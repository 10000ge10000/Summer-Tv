package com.github.tvbox.osc.ui.adapter;

import android.graphics.Color;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.bean.MovieSort;

import java.util.ArrayList;
import java.util.List;

/**
 * Android TV 分类页顶部的真实子分类选择器。
 *
 * <p>使用稳定 ID + payload 局部刷新更新选中态，避免 {@code notifyDataSetChanged()} 导致
 * TV 遥控器焦点从当前子分类项丢失并回退到左侧导航栏「搜索」。
 */
public class SubCategoryAdapter extends BaseQuickAdapter<MovieSort.SortData, BaseViewHolder> {
    private static final String PAYLOAD_SELECTION = "selection";
    private String selectedId;

    public SubCategoryAdapter() {
        super(R.layout.item_sub_category, new ArrayList<>());
        setHasStableIds(true);
    }

    @Override
    public long getItemId(int position) {
        MovieSort.SortData item = getItem(position);
        if (item == null || item.id == null) {
            return position;
        }
        return item.id.hashCode();
    }

    public void setSelectedId(String selectedId) {
        if (this.selectedId == null ? selectedId == null : this.selectedId.equals(selectedId)) {
            return;
        }
        String oldId = this.selectedId;
        this.selectedId = selectedId;
        int oldPos = findPositionById(oldId);
        int newPos = findPositionById(selectedId);
        if (oldPos >= 0) {
            notifyItemChanged(oldPos, PAYLOAD_SELECTION);
        }
        if (newPos >= 0 && newPos != oldPos) {
            notifyItemChanged(newPos, PAYLOAD_SELECTION);
        }
    }

    public int findPositionById(String id) {
        if (id == null) return -1;
        List<MovieSort.SortData> items = getData();
        for (int i = 0; i < items.size(); i++) {
            MovieSort.SortData item = items.get(i);
            if (item != null && id.equals(item.id)) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public void onBindViewHolder(@NonNull BaseViewHolder holder, int position, @NonNull List<Object> payloads) {
        if (payloads.contains(PAYLOAD_SELECTION)) {
            int dataPos = position - getHeaderLayoutCount();
            if (dataPos >= 0 && dataPos < getData().size()) {
                applySelectionState(holder, getData().get(dataPos));
                return;
            }
        }
        super.onBindViewHolder(holder, position, payloads);
    }

    @Override
    protected void convert(BaseViewHolder helper, MovieSort.SortData item) {
        String name = (item == null || item.name == null) ? "" : item.name;
        helper.setText(R.id.subCategoryTitle, name);
        applySelectionState(helper, item);
    }

    private void applySelectionState(BaseViewHolder helper, MovieSort.SortData item) {
        boolean selected = item != null && item.id != null && item.id.equals(selectedId);
        helper.itemView.setActivated(selected);
        TextView title = helper.getView(R.id.subCategoryTitle);
        title.setTextColor(selected ? Color.WHITE : 0xB3FFFFFF);
        title.getPaint().setFakeBoldText(selected);
        title.invalidate();
    }
}
