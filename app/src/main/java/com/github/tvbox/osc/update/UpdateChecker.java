package com.github.tvbox.osc.update;

import android.os.Handler;
import android.os.Looper;

import com.github.tvbox.osc.BuildConfig;
import com.github.tvbox.osc.util.LOG;
import com.google.gson.Gson;

import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * 应用内更新检查：读取 GitHub Releases 上的 update.json，判断是否有新版本。
 *
 * <p>只做检查，不下载、不安装 APK（v1.0.20 起由用户用浏览器下载后自行安装）。
 * 使用独立的 OkHttpClient 走系统信任链，不复用信任所有证书的全局 OkGo 客户端。
 */
public final class UpdateChecker {

    public interface Callback {
        /** 有可用更新。已在主线程回调。 */
        void onUpdateAvailable(UpdateInfo info);

        /** 无更新或检查失败，静默处理即可。已在主线程回调。 */
        void onNoUpdate();

        /** 网络失败或版本文件无法解析（区别于"确认没有新版本"）。默认按无更新处理。已在主线程回调。 */
        default void onCheckFailed() {
            onNoUpdate();
        }
    }

    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile OkHttpClient client;

    private UpdateChecker() {
    }

    /**
     * 独立客户端：默认 SSLSocketFactory 即系统信任链，不复用被削弱的全局实例。
     */
    private static OkHttpClient client() {
        if (client == null) {
            synchronized (UpdateChecker.class) {
                if (client == null) {
                    client = new OkHttpClient.Builder()
                            .connectTimeout(6, TimeUnit.SECONDS)
                            .readTimeout(60, TimeUnit.SECONDS)
                            .followSslRedirects(false)
                            .build();
                }
            }
        }
        return client;
    }

    public static void check(final String endpoint, final Callback callback) {
        if (endpoint == null || !endpoint.startsWith("https://")) {
            MAIN.post(callback::onNoUpdate);
            return;
        }
        IO.execute(() -> {
            UpdateInfo info = null;
            // 404 表示还没有发布任何 Release，属于"没有新版本"；所有源都连不上才算检查失败
            boolean failed = true;
            for (String prefix : UpdateMirrors.candidates(endpoint)) {
                try {
                    // update.json 托管在 GitHub Releases（latest/download 会 302 到 GitHub 的文件 CDN）
                    Request request = new Request.Builder().url(prefix + endpoint).get().build();
                    try (Response response = client().newCall(request).execute()) {
                        ResponseBody body = response.body();
                        if (response.isSuccessful() && body != null) {
                            UpdateInfo parsed = new Gson().fromJson(body.string(), UpdateInfo.class);
                            if (parsed != null && parsed.versionCode > 0) {
                                parsed.selectVariant(BuildConfig.FLAVOR);
                                info = parsed;
                                failed = false;
                                UpdateMirrors.remember(prefix);
                                break;
                            }
                        } else if (response.code() == 404 && prefix.isEmpty()) {
                            // GitHub 直连明确返回 404：还没有任何 Release，不必再试镜像
                            failed = false;
                            break;
                        }
                    }
                } catch (Throwable th) {
                    LOG.e("Update check failed via " + (prefix.isEmpty() ? "github" : prefix) + ": " + th.getMessage());
                }
            }
            final UpdateInfo result = info;
            final boolean checkFailed = failed;
            MAIN.post(() -> {
                if (result != null && result.isValid() && result.versionCode > BuildConfig.VERSION_CODE) {
                    callback.onUpdateAvailable(result);
                } else if (checkFailed) {
                    callback.onCheckFailed();
                } else {
                    callback.onNoUpdate();
                }
            });
        });
    }
}
