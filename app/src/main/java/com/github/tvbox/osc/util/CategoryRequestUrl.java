package com.github.tvbox.osc.util;

import okhttp3.HttpUrl;

/** 构造分类请求前，移除由客户端统一管理的查询参数。 */
public final class CategoryRequestUrl {
    private CategoryRequestUrl() {
    }

    public static String normalize(String api) {
        if (api == null || api.isEmpty()) return api;
        try {
            HttpUrl url = HttpUrl.parse(api);
            if (url == null) return api;
            return url.newBuilder()
                    .removeAllQueryParameters("ac")
                    .removeAllQueryParameters("t")
                    .removeAllQueryParameters("pg")
                    .build()
                    .toString();
        } catch (Throwable ignored) {
            return api;
        }
    }
}
