package com.github.tvbox.osc.ui.activity;

import android.widget.TextView;

import com.github.tvbox.osc.BuildConfig;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.base.BaseActivity;
import com.github.tvbox.osc.commercial.MonetizationManager;
import com.github.tvbox.osc.ui.dialog.TipDialog;
import com.github.tvbox.osc.util.FastClickCheckUtil;

/**
 * 关于页：应用名、版本和 about_content（简介、内容声明、隐私、开源说明）。
 * 设置页整体移除后由它顶替首页右上角入口，匿名统计开关也搬到这里。本轮不提供“检查更新”。
 */
public class AboutActivity extends BaseActivity {

    @Override
    protected int getLayoutResID() {
        return R.layout.activity_about;
    }

    @Override
    protected void init() {
        // 版本号从 BuildConfig 取，避免每次发版手改 strings.xml 造成版本漂移
        TextView version = findViewById(R.id.aboutVersion);
        version.setText(getString(R.string.about_version, BuildConfig.VERSION_NAME));
        findViewById(R.id.aboutScroll).requestFocus();

        findViewById(R.id.aboutDisclaimer).setOnClickListener(v -> {
            FastClickCheckUtil.check(v);
            com.github.tvbox.osc.ui.dialog.DisclaimerDialog.showReadOnly(this);
        });

        findViewById(R.id.aboutUpdate).setOnClickListener(v -> {
            FastClickCheckUtil.check(v);
            com.github.tvbox.osc.update.UpdateManager.checkManually(this);
        });

        TextView analytics = findViewById(R.id.aboutAnalytics);
        updateAnalyticsText(analytics);
        analytics.setOnClickListener(v -> {
            FastClickCheckUtil.check(v);
            if (MonetizationManager.analyticsAllowed()) {
                MonetizationManager.get().setAnalyticsConsent(false);
                updateAnalyticsText(analytics);
                return;
            }
            // 开启前必须展示采集说明并由用户确认，与原设置页行为一致
            final TipDialog[] holder = new TipDialog[1];
            holder[0] = new TipDialog(this,
                    getString(R.string.dia_analytics_desc),
                    getString(R.string.mn_analytics_on),
                    getString(R.string.hm_cancel),
                    new TipDialog.OnListener() {
                        @Override
                        public void left() {
                            MonetizationManager.get().setAnalyticsConsent(true);
                            updateAnalyticsText(analytics);
                            holder[0].dismiss();
                        }

                        @Override
                        public void right() {
                            holder[0].dismiss();
                        }

                        @Override
                        public void cancel() {
                        }
                    });
            holder[0].show();
        });
    }

    private void updateAnalyticsText(TextView view) {
        view.setText(getString(R.string.about_analytics,
                MonetizationManager.analyticsAllowed() ? "已开启" : "已关闭"));
    }
}
