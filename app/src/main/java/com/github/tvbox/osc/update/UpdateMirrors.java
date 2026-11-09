package com.github.tvbox.osc.update;

import java.util.ArrayList;
import java.util.List;

/**
 * 国内网络常连不上 GitHub：先直连，失败再依次走免费加速镜像（格式均为 前缀 + 原 GitHub 地址）。
 *
 * <p>2026-11-09 实测可用，均支持 releases/latest/download 跳转；镜像失效时删掉或替换即可。
 * 镜像可能篡改内容，但安装前会比对签名证书，篡改的包无法通过（SHA-256 与包同源，只防传输损坏）。
 */
final class UpdateMirrors {

    static final String[] PREFIXES = {
            "",
            "https://gh-proxy.com/",
            "https://ghfast.top/",
            "https://ghproxy.net/",
    };

    /** 最近一次连通的源，下载安装包时优先使用 */
    private static volatile String preferred = "";

    private UpdateMirrors() {
    }

    static void remember(String prefix) {
        preferred = prefix == null ? "" : prefix;
    }

    /** 依次尝试的地址前缀：只有 GitHub 地址才走镜像，最近连通的源排第一 */
    static List<String> candidates(String url) {
        List<String> list = new ArrayList<>();
        if (url == null || !url.startsWith("https://github.com/")) {
            list.add("");
            return list;
        }
        list.add(preferred);
        for (String prefix : PREFIXES) {
            if (!list.contains(prefix)) list.add(prefix);
        }
        return list;
    }
}
