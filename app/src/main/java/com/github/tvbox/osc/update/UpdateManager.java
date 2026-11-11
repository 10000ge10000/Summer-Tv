package com.github.tvbox.osc.update;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.ui.dialog.TipDialog;
import com.github.tvbox.osc.util.LOG;

/**
 * 更新流程：检查 → 提示 → 交给浏览器下载。
 *
 * <p>v1.0.20 起应用不再自己下载、安装 APK（不申请 REQUEST_INSTALL_PACKAGES）：
 * 应用内下载安装是 Play 保护机制等安全扫描判定"恶意下载器"的典型特征。
 * 发现新版本后只弹窗提示，用户确认后用浏览器打开安装包地址，由用户自己安装。
 *
 * <p>每次进程生命周期内只自动提示一次，避免用户每回到首页都被打断。
 */
public final class UpdateManager {

    private static boolean promptedThisSession;
    /** 本进程内是否已经发起过自动检查 */
    private static boolean autoCheckStarted;

    private UpdateManager() {
    }

    /** 进入首页时静默检查，只有发现新版本才提示 */
    public static void checkOnHome(final Activity activity) {
        if (activity == null || activity.isFinishing() || promptedThisSession || autoCheckStarted) return;
        // 首次使用声明还没同意时不检查，避免更新弹窗和声明弹窗叠在一起；同意后由声明回调再触发
        if (!com.github.tvbox.osc.ui.dialog.DisclaimerDialog.isAccepted()) return;
        String endpoint = activity.getString(R.string.update_check_url);
        if (endpoint == null || endpoint.trim().isEmpty()) return;
        autoCheckStarted = true;

        UpdateChecker.check(endpoint.trim(), new UpdateChecker.Callback() {
            @Override
            public void onUpdateAvailable(UpdateInfo info) {
                if (activity.isFinishing() || promptedThisSession) return;
                promptedThisSession = true;
                promptUpdate(activity, info);
            }

            @Override
            public void onNoUpdate() {
                // 静默：自动检查没有新版本或失败都不打扰用户
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

            @Override
            public void onCheckFailed() {
                if (!activity.isFinishing()) {
                    Toast.makeText(activity, R.string.update_failed, Toast.LENGTH_LONG).show();
                }
            }
        });
    }

    private static void promptUpdate(final Activity activity, final UpdateInfo info) {
        StringBuilder message = new StringBuilder();
        message.append(activity.getString(R.string.update_found, info.versionName));
        if (info.releaseNote != null && !info.releaseNote.trim().isEmpty()) {
            message.append("\n\n").append(info.releaseNote.trim());
        }
        message.append("\n\n").append(activity.getString(R.string.update_download_hint));
        String left = info.forceUpdate ? "退出" : "稍后";
        // TipDialog 的按钮不会自己关闭弹窗，需要在回调里 dismiss
        final TipDialog[] holder = new TipDialog[1];
        holder[0] = new TipDialog(activity, message.toString(), left,
                activity.getString(R.string.update_go_download),
                new TipDialog.OnListener() {
                    @Override
                    public void left() {
                        holder[0].dismiss();
                        if (info.forceUpdate) {
                            activity.finish();
                        }
                    }

                    @Override
                    public void right() {
                        holder[0].dismiss();
                        openDownload(activity, info);
                    }

                    @Override
                    public void cancel() {
                    }
                });
        holder[0].show();
    }

    /** 用浏览器打开安装包地址；GitHub 地址优先走检查时连通的加速源 */
    private static void openDownload(Activity activity, UpdateInfo info) {
        String url = UpdateMirrors.candidates(info.apkUrl).get(0) + info.apkUrl;
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(intent);
        } catch (Throwable th) {
            LOG.e("Open download url failed: " + th.getMessage());
            Toast.makeText(activity, activity.getString(R.string.update_no_browser, url), Toast.LENGTH_LONG).show();
        }
    }
}
