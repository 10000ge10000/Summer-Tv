package com.github.tvbox.osc.ui.dialog;

import android.app.Activity;
import android.text.method.ScrollingMovementMethod;
import android.view.KeyEvent;
import android.view.View;
import android.widget.TextView;

import com.github.tvbox.osc.R;
import com.orhanobut.hawk.Hawk;

/**
 * 首次使用的使用声明 / 免责提示。
 *
 * <p>用户点"同意并继续"后记录声明版本号，以后不再弹出；声明内容有实质修改时把
 * {@link #VERSION} 加 1，所有用户会在下次打开时重新确认。"不同意"或返回键直接退出应用。
 * 关于页"使用声明"按钮可随时以只读方式再看（{@link #showReadOnly}）。
 */
public class DisclaimerDialog extends BaseDialog {

    /** 声明内容版本：修改条款后递增 */
    public static final int VERSION = 1;
    private static final String KEY_ACCEPTED = "disclaimer_accepted_version";

    public interface OnAccepted {
        void onAccepted();
    }

    public static boolean isAccepted() {
        return Hawk.get(KEY_ACCEPTED, 0) >= VERSION;
    }

    /** 首次使用：必须选择同意或退出 */
    public static void showIfNeeded(final Activity activity, final OnAccepted callback) {
        if (activity == null || activity.isFinishing() || isAccepted()) return;
        new DisclaimerDialog(activity, false, callback).show();
    }

    /** 关于页查看：只有一个"关闭"按钮 */
    public static void showReadOnly(Activity activity) {
        if (activity == null || activity.isFinishing()) return;
        new DisclaimerDialog(activity, true, null).show();
    }

    private DisclaimerDialog(final Activity activity, boolean readOnly, final OnAccepted callback) {
        super(activity);
        setContentView(R.layout.dialog_disclaimer);
        setCanceledOnTouchOutside(false);
        setCancelable(readOnly);
        TextView body = findViewById(R.id.disclaimerBody);
        body.setMovementMethod(new ScrollingMovementMethod());
        TextView agree = findViewById(R.id.disclaimerAgree);
        TextView exit = findViewById(R.id.disclaimerExit);
        if (readOnly) {
            agree.setText(R.string.disclaimer_close);
            exit.setVisibility(View.GONE);
            agree.setOnClickListener(v -> dismiss());
        } else {
            agree.setOnClickListener(v -> {
                Hawk.put(KEY_ACCEPTED, VERSION);
                dismiss();
                if (callback != null) callback.onAccepted();
            });
            exit.setOnClickListener(v -> {
                dismiss();
                activity.finish();
            });
            // 首次弹窗时返回键等同"不同意并退出"，避免误触后直接进入应用
            setOnKeyListener((d, keyCode, event) -> {
                if (keyCode == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_UP) {
                    dismiss();
                    activity.finish();
                    return true;
                }
                return false;
            });
        }
        // 遥控器：正文可用上下键滚动，默认焦点放在正文上，读完再按下键到按钮
        body.requestFocus();
    }
}
