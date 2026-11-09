package com.github.tvbox.osc.commercial;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.bumptech.glide.Glide;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.util.LOG;

public class RemoteAdProvider implements AdProvider {
    @Override
    public void showPlacement(Activity activity, ViewGroup parent, AdPlacement placement) {
        CommercialConfig config = MonetizationManager.get().getConfig();
        if (activity == null || parent == null || config == null || !config.adsEnabled) return;
        AdSlot slot = config.findSlot(placement);
        if (slot == null) return;
        String tag = "commercial_ad_" + placement.name();
        if (parent.findViewWithTag(tag) != null) return;

        LinearLayout container = new LinearLayout(activity);
        container.setTag(tag);
        container.setGravity(Gravity.CENTER_VERTICAL);
        container.setOrientation(LinearLayout.HORIZONTAL);
        container.setPadding(18, 10, 18, 10);
        container.setBackgroundColor(Color.argb(210, 26, 32, 39));
        // 只有能点开的广告才进入遥控焦点链。无跳转地址时设为可聚焦，
        // 用户在首页四向移动会白撞上一个按了没反应的控件。
        boolean clickable = slot.clickUrl != null && !slot.clickUrl.trim().isEmpty();
        container.setFocusable(clickable);
        container.setClickable(clickable);

        if (slot.imageUrl != null && !slot.imageUrl.trim().isEmpty()) {
            ImageView image = new ImageView(activity);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            LinearLayout.LayoutParams imageParams = new LinearLayout.LayoutParams(96, 54);
            imageParams.setMargins(0, 0, 16, 0);
            container.addView(image, imageParams);
            Glide.with(activity).load(slot.imageUrl).into(image);
        }

        TextView text = new TextView(activity);
        text.setTextColor(activity.getResources().getColor(R.color.color_FFFFFF));
        text.setTextSize(18);
        text.setSingleLine(false);
        String description = slot.description == null ? "" : slot.description.trim();
        text.setText(description.isEmpty() ? slot.title : slot.title + "  " + description);
        container.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        if (clickable) {
            container.setOnClickListener(v -> {
                MonetizationManager.get().trackAdClick(placement);
                try {
                    activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(slot.clickUrl)));
                } catch (Throwable th) {
                    // 电视上常常没有浏览器，静默失败会让用户以为是应用卡了
                    LOG.e("Ad click failed: " + th.getMessage());
                    Toast.makeText(activity, R.string.ad_open_failed, Toast.LENGTH_SHORT).show();
                }
            });
        }

        // 追加到末尾而不是插到索引 0：首页 contentLayout 是纵向容器，
        // 索引 0 会把分类行和内容区整体下推，构成 AGENTS.md 明令禁止的首页布局抖动。
        parent.addView(container, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        MonetizationManager.get().trackAdShow(placement);
    }
}
