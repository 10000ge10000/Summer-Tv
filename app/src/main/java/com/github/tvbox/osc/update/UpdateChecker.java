package com.github.tvbox.osc.update;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import com.github.tvbox.osc.BuildConfig;
import com.github.tvbox.osc.util.LOG;
import com.google.gson.Gson;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * 应用内更新检查与下载。
 *
 * <p>本应用通过旁加载分发，没有应用商店的自动更新通道；已分发版本一旦出问题
 * 无法触达存量设备。此类提供唯一的召回路径。
 *
 * <p>安全约束（三条，缺一不可）：
 * <ul>
 *   <li>使用独立的 OkHttpClient，走系统信任链。全局 OkGo 客户端安装了
 *       trust-all TrustManager 并放行明文流量，不能用于分发可执行文件。</li>
 *   <li>下载完成后校验 SHA-256。</li>
 *   <li>唤起安装前比对下载包与已安装应用的签名证书。sha256 与 APK 来自同一端点，
 *       同源校验不构成保护；签名比对才是真正的信任锚。</li>
 * </ul>
 */
public final class UpdateChecker {

    public interface Callback {
        /** 有可用更新。已在主线程回调。 */
        void onUpdateAvailable(UpdateInfo info);

        /** 无更新或检查失败，静默处理即可。已在主线程回调。 */
        void onNoUpdate();
    }

    public interface DownloadCallback {
        void onProgress(int percent);

        /** 下载与全部校验均通过，file 可直接用于唤起安装。已在主线程回调。 */
        void onReady(File file);

        void onFailed(String reason);
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
                            .connectTimeout(15, TimeUnit.SECONDS)
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
            try {
                // update.json 托管在 GitHub Releases（latest/download 会 302 到 GitHub 的文件 CDN），
                // 不再附带版本、渠道等查询参数
                Request request = new Request.Builder().url(endpoint).get().build();
                try (Response response = client().newCall(request).execute()) {
                    ResponseBody body = response.body();
                    if (response.isSuccessful() && body != null) {
                        info = new Gson().fromJson(body.string(), UpdateInfo.class);
                        if (info != null) {
                            info.selectVariant(BuildConfig.FLAVOR);
                        }
                    }
                }
            } catch (Throwable th) {
                LOG.e("Update check failed: " + th.getMessage());
            }
            final UpdateInfo result = info;
            MAIN.post(() -> {
                if (result != null && result.isValid() && result.versionCode > BuildConfig.VERSION_CODE) {
                    callback.onUpdateAvailable(result);
                } else {
                    callback.onNoUpdate();
                }
            });
        });
    }

    public static void download(final Context context, final UpdateInfo info,
                                final DownloadCallback callback) {
        final Context app = context.getApplicationContext();
        IO.execute(() -> {
            File target = null;
            String failure = null;
            try {
                File dir = app.getExternalFilesDir("apk");
                if (dir == null) {
                    throw new IllegalStateException("外部存储不可用");
                }
                if (!dir.isDirectory() && !dir.mkdirs()) {
                    throw new IllegalStateException("无法创建下载目录");
                }
                File file = new File(dir, "update-" + info.versionCode + ".apk");
                //noinspection ResultOfMethodCallIgnored
                file.delete();

                Request request = new Request.Builder().url(info.apkUrl).get().build();
                try (Response response = client().newCall(request).execute()) {
                    ResponseBody body = response.body();
                    if (!response.isSuccessful() || body == null) {
                        throw new IllegalStateException("下载失败：HTTP " + response.code());
                    }
                    writeToFile(body, file, callback);
                }

                String actual = sha256(file);
                if (!info.sha256.equalsIgnoreCase(actual)) {
                    //noinspection ResultOfMethodCallIgnored
                    file.delete();
                    throw new IllegalStateException("安装包校验失败，已丢弃");
                }
                if (!isSignedBySameCertificate(app, file)) {
                    //noinspection ResultOfMethodCallIgnored
                    file.delete();
                    throw new IllegalStateException("安装包签名与当前应用不一致，已丢弃");
                }
                target = file;
            } catch (Throwable th) {
                failure = th.getMessage() == null ? "更新失败" : th.getMessage();
                LOG.e("Update download failed: " + failure);
            }
            final File ready = target;
            final String reason = failure;
            MAIN.post(() -> {
                if (ready != null) {
                    callback.onReady(ready);
                } else {
                    callback.onFailed(reason);
                }
            });
        });
    }

    private static void writeToFile(ResponseBody body, File file, DownloadCallback callback)
            throws Exception {
        long total = body.contentLength();
        long written = 0;
        int lastPercent = -1;
        byte[] buffer = new byte[8192];
        try (InputStream in = body.byteStream(); OutputStream out = new FileOutputStream(file)) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                written += read;
                if (total > 0) {
                    int percent = (int) (written * 100 / total);
                    if (percent != lastPercent) {
                        lastPercent = percent;
                        final int value = percent;
                        MAIN.post(() -> callback.onProgress(value));
                    }
                }
            }
        }
    }

    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[8192];
        try (InputStream in = new java.io.FileInputStream(file)) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : digest.digest()) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    /**
     * 比对下载包与已安装应用的签名证书。
     *
     * <p>包管理器在覆盖安装时本就会拒绝签名不一致的包，但那时用户已经走完下载与确认
     * 流程才看到失败。提前比对既能给出清楚的失败原因，也能避免用户为了"装上"而
     * 先卸载正版再安装被替换的包。
     */
    private static boolean isSignedBySameCertificate(Context context, File apk) {
        try {
            PackageManager pm = context.getPackageManager();
            Set<String> installed = signatureDigests(
                    pm.getPackageInfo(context.getPackageName(), signatureFlag()));
            PackageInfo downloaded = pm.getPackageArchiveInfo(apk.getAbsolutePath(), signatureFlag());
            if (downloaded == null) return false;
            Set<String> candidate = signatureDigests(downloaded);
            return !installed.isEmpty() && installed.equals(candidate);
        } catch (Throwable th) {
            LOG.e("Signature check failed: " + th.getMessage());
            return false;
        }
    }

    @SuppressWarnings("deprecation")
    private static int signatureFlag() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                ? PackageManager.GET_SIGNING_CERTIFICATES
                : PackageManager.GET_SIGNATURES;
    }

    @SuppressWarnings("deprecation")
    private static Set<String> signatureDigests(PackageInfo info) throws Exception {
        Signature[] signatures;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && info.signingInfo != null) {
            signatures = info.signingInfo.hasMultipleSigners()
                    ? info.signingInfo.getApkContentsSigners()
                    : info.signingInfo.getSigningCertificateHistory();
        } else {
            signatures = info.signatures;
        }
        Set<String> digests = new HashSet<>();
        if (signatures == null) return digests;
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (Signature signature : signatures) {
            if (signature == null) continue;
            digests.add(Arrays.toString(digest.digest(signature.toByteArray())));
        }
        return digests;
    }
}
