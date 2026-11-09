package com.github.tvbox.osc.update;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.ui.dialog.BaseDialog;
import com.github.tvbox.osc.ui.dialog.TipDialog;
import com.github.tvbox.osc.util.LOG;

import java.io.File;

/**
 * 更新流程编排：检查 → 提示 → 下载 → 唤起安装。
 *
 * <p>每次进程生命周期内只提示一次，避免用户每回到首页都被打断。
 */
public final class UpdateManager {

    private static boolean promptedThisSession;
    /** 等用户在系统设置里允许"安装未知应用"后，回到应用时继续安装的包 */
    private static File pendingInstall;

    private UpdateManager() {
    }

    public static void checkOnHome(final Activity activity) {
        if (activity == null || activity.isFinishing() || promptedThisSession) return;
        String endpoint = activity.getString(R.string.update_check_url);
        if (endpoint == null || endpoint.trim().isEmpty()) return;

        UpdateChecker.check(endpoint.trim(), new UpdateChecker.Callback() {
            @Override
            public void onUpdateAvailable(UpdateInfo info) {
                if (activity.isFinishing()) return;
                promptedThisSession = true;
                promptUpdate(activity, info);
            }

            @Override
            public void onNoUpdate() {
                // 静默：更新检查失败不应打扰用户
            }
        });
    }

    /** 关于页"检查更新"：手动触发，无更新或失败时给出提示 */
    public static void checkManually(final Activity activity) {
        if (activity == null || activity.isFinishing()) return;
        String endpoint = activity.getString(R.string.update_check_url);
        Toast.makeText(activity, R.string.update_checking, Toast.LENGTH_SHORT).show();
        UpdateChecker.check(endpoint == null ? null : endpoint.trim(), new UpdateChecker.Callback() {
            @Override
            public void onUpdateAvailable(UpdateInfo info) {
                if (activity.isFinishing()) return;
                promptedThisSession = true;
                promptUpdate(activity, info);
            }

            @Override
            public void onNoUpdate() {
                if (!activity.isFinishing()) {
                    Toast.makeText(activity, R.string.update_latest, Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    /** 从系统"安装未知应用"设置页返回后调用：已授权就继续安装刚下载好的包 */
    public static void resumePendingInstall(Activity activity) {
        if (pendingInstall == null || activity == null || activity.isFinishing()) return;
        if (!canInstallPackages(activity)) return;
        File apk = pendingInstall;
        pendingInstall = null;
        if (apk.exists()) {
            launchInstaller(activity, apk);
        }
    }

    private static void promptUpdate(final Activity activity, final UpdateInfo info) {
        StringBuilder message = new StringBuilder();
        message.append("发现新版本 ").append(info.versionName);
        if (info.releaseNote != null && !info.releaseNote.trim().isEmpty()) {
            message.append("\n\n").append(info.releaseNote.trim());
        }
        String left = info.forceUpdate ? "退出" : "稍后";
        TipDialog dialog = new TipDialog(activity, message.toString(), left, "立即更新",
                new TipDialog.OnListener() {
                    @Override
                    public void left() {
                        if (info.forceUpdate) {
                            activity.finish();
                        }
                    }

                    @Override
                    public void right() {
                        startDownload(activity, info);
                    }

                    @Override
                    public void cancel() {
                    }
                });
        dialog.setOnDismissListener(d -> {
        });
        dialog.show();
    }

    private static void startDownload(final Activity activity, UpdateInfo info) {
        final ProgressDialog progress = new ProgressDialog(activity);
        progress.show();
        UpdateChecker.download(activity, info, new UpdateChecker.DownloadCallback() {
            @Override
            public void onProgress(int percent) {
                progress.setPercent(percent);
            }

            @Override
            public void onReady(File file) {
                progress.dismissSafely();
                install(activity, file);
            }

            @Override
            public void onFailed(String reason) {
                progress.dismissSafely();
                if (!activity.isFinishing()) {
                    Toast.makeText(activity, reason == null ? "更新失败" : reason,
                            Toast.LENGTH_LONG).show();
                }
            }
        });
    }

    /**
     * Android 8.0 起每个应用都要单独获得"安装未知应用"权限，否则系统或电视厂商的安装器会直接拦截。
     * 未授权时先说明原因并带用户去授权页，返回后由 {@link #resumePendingInstall} 继续安装。
     */
    private static void install(final Activity activity, final File apk) {
        if (canInstallPackages(activity)) {
            launchInstaller(activity, apk);
            return;
        }
        pendingInstall = apk;
        TipDialog dialog = new TipDialog(activity, activity.getString(R.string.update_need_permission),
                activity.getString(R.string.update_go_settings), activity.getString(R.string.hm_cancel),
                new TipDialog.OnListener() {
                    @Override
                    public void left() {
                        try {
                            Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    Uri.parse("package:" + activity.getPackageName()));
                            activity.startActivity(intent);
                        } catch (Throwable th) {
                            // 部分电视系统没有这个设置页：直接交给安装器，由安装器自己提示
                            pendingInstall = null;
                            launchInstaller(activity, apk);
                        }
                    }

                    @Override
                    public void right() {
                        pendingInstall = null;
                    }

                    @Override
                    public void cancel() {
                    }
                });
        dialog.show();
    }

    private static boolean canInstallPackages(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true;
        try {
            return context.getPackageManager().canRequestPackageInstalls();
        } catch (Throwable th) {
            return true;
        }
    }

    private static void launchInstaller(Context context, File apk) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            Uri uri;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                uri = FileProvider.getUriForFile(
                        context, context.getPackageName() + ".fileprovider", apk);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } else {
                uri = Uri.fromFile(apk);
            }
            intent.setDataAndType(uri, "application/vnd.android.package-archive");
            context.startActivity(intent);
        } catch (Throwable th) {
            LOG.e("Launch installer failed: " + th.getMessage());
            Toast.makeText(context, "无法唤起安装程序，请手动安装：" + apk.getAbsolutePath(),
                    Toast.LENGTH_LONG).show();
        }
    }

    /** 复用 dialog_tip 布局显示下载进度，隐藏两个按钮，不新增 XML。 */
    private static final class ProgressDialog extends BaseDialog {
        private final TextView info;

        ProgressDialog(Context context) {
            super(context);
            setContentView(R.layout.dialog_tip);
            setCanceledOnTouchOutside(false);
            setCancelable(false);
            info = findViewById(R.id.tipInfo);
            findViewById(R.id.leftBtn).setVisibility(View.GONE);
            findViewById(R.id.rightBtn).setVisibility(View.GONE);
            info.setText("正在下载更新…");
        }

        void setPercent(int percent) {
            info.setText("正在下载更新… " + percent + "%");
        }

        void dismissSafely() {
            try {
                if (isShowing()) dismiss();
            } catch (Throwable ignored) {
                // Activity 已销毁时 dismiss 会抛异常，此处无需处理
            }
        }
    }
}
