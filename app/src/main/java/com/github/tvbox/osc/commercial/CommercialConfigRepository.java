package com.github.tvbox.osc.commercial;

import android.content.Context;

import com.github.tvbox.osc.BuildConfig;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.LOG;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.orhanobut.hawk.Hawk;

import java.io.IOException;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.Request;

public class CommercialConfigRepository {
    private static final long CONFIG_TTL_MS = 6L * 60L * 60L * 1000L;
    private static final String CACHE_CONFIG = "commercial_config_cache";
    private static final String CACHE_TIME = "commercial_config_cache_time";

    private final Gson gson = new Gson();
    private CommercialConfig currentConfig = CommercialConfig.disabled();
    // 主线程与 OkHttp 回调线程都会读写，缺少可见性会导致并发重复刷新
    private volatile boolean loading;

    public CommercialConfig getCurrentConfig() {
        return currentConfig == null ? CommercialConfig.disabled() : currentConfig;
    }

    public void loadCachedConfig(Context context) {
        String cached = Hawk.get(CACHE_CONFIG, "");
        if (cached == null || cached.isEmpty()) {
            currentConfig = CommercialConfig.disabled();
            return;
        }
        CommercialConfig parsed = parseSignedConfig(context, cached);
        currentConfig = parsed == null ? CommercialConfig.disabled() : parsed;
    }

    public void refreshIfNeeded(Context context, boolean force) {
        if (loading || context == null) return;
        long cachedAt = Hawk.get(CACHE_TIME, 0L);
        if (!force && System.currentTimeMillis() - cachedAt < CONFIG_TTL_MS) {
            if (currentConfig == null) loadCachedConfig(context);
            return;
        }
        String url = context.getString(R.string.commercial_config_url);
        if (url == null || url.trim().isEmpty()) {
            currentConfig = CommercialConfig.disabled();
            return;
        }
        loading = true;
        // 走独立客户端与系统信任链，不复用全局的 trust-all 实例
        HttpUrl base = HttpUrl.parse(url.trim());
        if (base == null) {
            loading = false;
            currentConfig = CommercialConfig.disabled();
            return;
        }
        HttpUrl full = base.newBuilder()
                .addQueryParameter("app_version", BuildConfig.VERSION_NAME)
                .addQueryParameter("channel", BuildConfig.FLAVOR)
                .addQueryParameter("device_type", "tv")
                .build();
        CommercialHttp.client().newCall(new Request.Builder().url(full).get().build())
                .enqueue(new Callback() {
                    @Override
                    public void onFailure(Call call, IOException e) {
                        loading = false;
                        LOG.e("Commercial config fetch failed: " + e.getMessage());
                        loadCachedConfig(context);
                    }

                    @Override
                    public void onResponse(Call call, okhttp3.Response response) {
                        loading = false;
                        String body = null;
                        try {
                            if (response.isSuccessful() && response.body() != null) {
                                body = response.body().string();
                            }
                        } catch (Throwable th) {
                            LOG.e("Commercial config read failed: " + th.getMessage());
                        } finally {
                            response.close();
                        }
                        CommercialConfig parsed = body == null ? null : parseSignedConfig(context, body);
                        if (parsed == null) {
                            loadCachedConfig(context);
                            return;
                        }
                        currentConfig = parsed;
                        Hawk.put(CACHE_CONFIG, body);
                        Hawk.put(CACHE_TIME, System.currentTimeMillis());
                    }
                });
    }

    private CommercialConfig parseSignedConfig(Context context, String body) {
        try {
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            String publicKey = context.getString(R.string.commercial_config_public_key);

            // 优先走 payload_b64：签名覆盖的就是 base64 里那段字节本身，
            // 不依赖客户端能把 JSON 重新序列化成与服务端逐字节相同的形式。
            // 旧格式对 Gson 的 toString() 结果签名，任一端 JSON 库行为变化都会
            // 让全量设备验签失败并静默退回禁用配置。
            String payloadJson = null;
            if (root.has("payload_b64") && root.has("signature_b64")) {
                String payloadB64 = root.get("payload_b64").getAsString();
                String signatureB64 = root.get("signature_b64").getAsString();
                if (CommercialSecurity.verifySignature(payloadB64, signatureB64, publicKey)) {
                    payloadJson = new String(
                            android.util.Base64.decode(payloadB64, android.util.Base64.DEFAULT),
                            java.nio.charset.StandardCharsets.UTF_8);
                } else {
                    LOG.e("Commercial config signature invalid (b64)");
                    return null;
                }
            }

            JsonObject payload;
            if (payloadJson != null) {
                payload = JsonParser.parseString(payloadJson).getAsJsonObject();
            } else {
                // 兼容只下发旧格式的服务端
                payload = root.getAsJsonObject("payload");
                String signature = root.has("signature") ? root.get("signature").getAsString() : "";
                if (!CommercialSecurity.verifySignature(payload.toString(), signature, publicKey)) {
                    LOG.e("Commercial config signature invalid");
                    return null;
                }
            }

            CommercialConfigEnvelope.CommercialConfigPayload parsedPayload =
                    gson.fromJson(payload, CommercialConfigEnvelope.CommercialConfigPayload.class);
            CommercialConfig config = parsedPayload.toConfig();
            if (!isRolloutAllowed(config.rolloutPercent)) return CommercialConfig.disabled();
            if (!isMinVersionAllowed(config.minAppVersion)) return CommercialConfig.disabled();
            Hawk.put(HawkConfig.COMMERCIAL_REMOTE_READY, true);
            return config;
        } catch (Throwable th) {
            LOG.e("Commercial config parse failed: " + th.getMessage());
            return null;
        }
    }

    private boolean isRolloutAllowed(int rolloutPercent) {
        if (rolloutPercent >= 100) return true;
        if (rolloutPercent <= 0) return false;
        String deviceId = MonetizationManager.get().getDeviceId();
        int bucket = Math.abs(deviceId.hashCode()) % 100;
        return bucket < rolloutPercent;
    }

    private boolean isMinVersionAllowed(String minAppVersion) {
        if (minAppVersion == null || minAppVersion.trim().isEmpty()) return true;
        return compareVersion(BuildConfig.VERSION_NAME, minAppVersion.trim()) >= 0;
    }

    /**
     * 按段做数值比较。
     *
     * <p>原实现用 {@link String#compareTo}，字典序下 "1.0.9" 大于 "1.0.10"，
     * 版本号进入两位数后灰度判断会整体失准。
     */
    static int compareVersion(String left, String right) {
        String[] a = splitVersion(left);
        String[] b = splitVersion(right);
        int len = Math.max(a.length, b.length);
        for (int i = 0; i < len; i++) {
            int x = i < a.length ? parseSegment(a[i]) : 0;
            int y = i < b.length ? parseSegment(b[i]) : 0;
            if (x != y) return x < y ? -1 : 1;
        }
        return 0;
    }

    private static String[] splitVersion(String version) {
        if (version == null) return new String[0];
        // 去掉 "1.0.17-beta" 之类的后缀，只比较数字段
        String core = version.trim().split("[-+ ]")[0];
        return core.isEmpty() ? new String[0] : core.split("\\.");
    }

    private static int parseSegment(String segment) {
        try {
            return Integer.parseInt(segment.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
