package com.github.tvbox.osc.update;

import com.google.gson.annotations.SerializedName;

import java.util.Map;

/**
 * 更新描述文件 update.json 的结构（随每个 GitHub Release 一起上传）。
 *
 * <p>同一版本有多个构建变体（abi × brand × mode），{@link #apks} 以 BuildConfig.FLAVOR
 * （如 arm64GenericNormal）为键给出各自的下载地址和 SHA-256；顶层 apkUrl / sha256
 * 仅作兼容旧格式的兜底。
 *
 * <p>字段必须带 {@link SerializedName}：release 构建开启了 minify，
 * 裸字段名会被 R8 改写成 a/b/c，导致反序列化全部落空。
 */
public class UpdateInfo {

    @SerializedName("versionCode")
    public int versionCode;

    @SerializedName("versionName")
    public String versionName;

    @SerializedName("apkUrl")
    public String apkUrl;

    /** APK 的 SHA-256，十六进制小写。 */
    @SerializedName("sha256")
    public String sha256;

    @SerializedName("forceUpdate")
    public boolean forceUpdate;

    @SerializedName("releaseNote")
    public String releaseNote;

    @SerializedName("apks")
    public Map<String, Asset> apks;

    public static class Asset {
        @SerializedName("url")
        public String url;

        @SerializedName("sha256")
        public String sha256;
    }

    /** 按当前构建变体挑出对应安装包；没有该变体时保留顶层字段 */
    public void selectVariant(String flavor) {
        if (apks == null || flavor == null) return;
        Asset asset = apks.get(flavor);
        if (asset != null && asset.url != null && asset.sha256 != null) {
            apkUrl = asset.url;
            sha256 = asset.sha256.trim().toLowerCase(java.util.Locale.ROOT);
        }
    }

    public boolean isValid() {
        return versionCode > 0
                && apkUrl != null && apkUrl.startsWith("https://")
                && sha256 != null && sha256.length() == 64;
    }
}
