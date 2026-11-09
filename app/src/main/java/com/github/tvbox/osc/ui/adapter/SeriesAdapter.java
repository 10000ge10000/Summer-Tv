package com.github.tvbox.osc.ui.adapter;

import androidx.core.content.ContextCompat;
import android.widget.TextView;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.base.BaseActivity;
import com.github.tvbox.osc.bean.VodInfo;

import java.util.ArrayList;

/**
 * @author pj567
 * @date :2020/12/22
 * @description:
 */
public class SeriesAdapter extends BaseQuickAdapter<VodInfo.VodSeries, BaseViewHolder> {
    public SeriesAdapter() {
        super(R.layout.item_series, new ArrayList<>());
    }

    @Override
    protected void convert(BaseViewHolder helper, VodInfo.VodSeries item) {
        TextView tvSeries = helper.getView(R.id.tvSeries);
        if (item.selected) {
            // takagen99: Added Theme Color
//            tvSeries.setTextColor(mContext.getResources().getColor(R.color.color_theme));
            tvSeries.setTextColor(((BaseActivity) mContext).getThemeColor());
        } else {
            // 随焦点切换：聚焦时浅色底配深色字，其余白字
            tvSeries.setTextColor(ContextCompat.getColorStateList(mContext, R.color.st_button_text));
        }
        helper.setText(R.id.tvSeries, item.name);
    }
}