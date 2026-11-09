package com.github.tvbox.osc.commercial;

import android.content.Context;

import com.github.tvbox.osc.R;

import java.util.concurrent.TimeUnit;

import okhttp3.CertificatePinner;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;

/**
 * 商业配置与统计上报专用的 HTTP 客户端。
 *
 * <p>全局 OkGo 客户端安装了空实现的 TrustManager 与 UnSafeHostnameVerifier，
 * 并放行明文流量——那是为了兼容用户自填的第三方源（证书普遍不规范），
 * 但同一条通道上的商业配置会被同网段中间人完整解密并篡改，
 * 而商业配置里带着统计上报地址与广告跳转地址。
 *
 * <p>本客户端使用系统默认信任链，不做任何降级。自有域名证书由我们自己掌握，
 * 没有迁就不规范证书的理由。
 */
public final class CommercialHttp {

    private static volatile OkHttpClient client;
    private static volatile Context applicationContext;

    private CommercialHttp() {
    }

    public static void initialize(Context context) {
        if (context == null) return;
        Context appContext = context.getApplicationContext();
        synchronized (CommercialHttp.class) {
            if (applicationContext != appContext) {
                applicationContext = appContext;
                client = null;
            }
        }
    }

    public static OkHttpClient client() {
        if (client == null) {
            synchronized (CommercialHttp.class) {
                if (client == null) {
                    OkHttpClient.Builder builder = new OkHttpClient.Builder()
                            .connectTimeout(15, TimeUnit.SECONDS)
                            .readTimeout(30, TimeUnit.SECONDS)
                            .writeTimeout(30, TimeUnit.SECONDS)
                            // 不跟随 https -> http 降级跳转
                            .followSslRedirects(false);
                    CertificatePinner pinner = buildCertificatePinner(applicationContext);
                    if (pinner != null) {
                        builder.certificatePinner(pinner);
                    }
                    client = builder.build();
                }
            }
        }
        return client;
    }

    static CertificatePinner buildCertificatePinner(Context context) {
        if (context == null) return null;
        String hostUrl = context.getString(R.string.commercial_config_url);
        HttpUrl url = HttpUrl.parse(hostUrl == null ? "" : hostUrl.trim());
        if (url == null || url.host().isEmpty()) return null;

        String primary = context.getString(R.string.commercial_config_pin_primary).trim();
        String backup = context.getString(R.string.commercial_config_pin_backup).trim();
        return buildCertificatePinner(url.host(), primary, backup);
    }

    static CertificatePinner buildCertificatePinner(String host, String primary, String backup) {
        if (host == null || host.trim().isEmpty()) return null;
        // 双 pin 才启用：空值、格式不对或两项相同都保持系统信任链，避免单 pin 把全量设备锁死。
        if (!isSha256Pin(primary) || !isSha256Pin(backup) || primary.equals(backup)) return null;
        try {
            return new CertificatePinner.Builder()
                    .add(host.trim(), primary, backup)
                    .build();
        } catch (IllegalArgumentException ignored) {
            // 配置错误时维持默认关闭，不能因为 pin 写错阻断商业配置和统计客户端初始化。
            return null;
        }
    }

    private static boolean isSha256Pin(String value) {
        return value != null && value.startsWith("sha256/") && value.length() > "sha256/".length();
    }
}
