package com.github.tvbox.osc.live;

/**
 * 回看（时移）地址拼接。
 */
public final class LiveCatchup {

    private LiveCatchup() {
    }

    /**
     * 追加 playseek 参数：地址本身已有查询串时用 &amp; 拼接，否则用 ?。
     *
     * @param playseek 形如 yyyyMMddHHmmss-yyyyMMddHHmmss
     */
    public static String buildPlayseekUrl(String url, String playseek) {
        if (url == null) return null;
        int hash = url.indexOf('#');
        String fragment = hash >= 0 ? url.substring(hash) : "";
        String base = hash >= 0 ? url.substring(0, hash) : url;
        String sep = base.contains("?") ? (base.endsWith("?") || base.endsWith("&") ? "" : "&") : "?";
        return base + sep + "playseek=" + playseek + fragment;
    }
}
