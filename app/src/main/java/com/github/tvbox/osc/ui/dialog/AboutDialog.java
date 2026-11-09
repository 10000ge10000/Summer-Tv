package com.github.tvbox.osc.ui.dialog;

import android.content.Context;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.github.tvbox.osc.BuildConfig;
import com.github.tvbox.osc.R;

import org.jetbrains.annotations.NotNull;

public class AboutDialog extends BaseDialog {

    public AboutDialog(@NonNull @NotNull Context context) {
        super(context);
        setContentView(R.layout.dialog_about);
        // 版本号从 BuildConfig 取，避免每次发版手改 strings.xml 造成版本漂移
        TextView content = findViewById(R.id.aboutContent);
        content.setText(context.getString(R.string.about_content)
                + "\n\n" + context.getString(R.string.about_version, BuildConfig.VERSION_NAME));
    }
}