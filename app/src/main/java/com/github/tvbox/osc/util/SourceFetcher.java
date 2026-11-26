package com.github.tvbox.osc.util;

import android.text.TextUtils;

import com.github.catvod.crawler.Spider;
import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.bean.AbsJson;
import com.github.tvbox.osc.bean.AbsXml;
import com.github.tvbox.osc.bean.Movie;
import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.bean.VodInfo;
import com.google.gson.Gson;
import com.thoughtworks.xstream.XStream;
import com.thoughtworks.xstream.io.xml.DomDriver;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * 轻量级同步站点搜索 / 详情拉取器（独立 OkHttp 实例，不与全局 OkGo 的 search/detail tag 互相取消）。
 * 专供 {@link SmartSourceResolver} 与搜索页「仅看可播」后台探测使用。
 */
public final class SourceFetcher {

    private static final int CONNECT_TIMEOUT_MS = 2800;
    private static final int READ_TIMEOUT_MS = 3800;
    private static final String UA = "okHttp/Mod-1.5.0.0";
    private static final Gson GSON = new Gson();
    private static volatile OkHttpClient client;

    private SourceFetcher() {
    }

    private static OkHttpClient http() {
        if (client == null) {
            synchronized (SourceFetcher.class) {
                if (client == null) {
                    client = new OkHttpClient.Builder()
                            .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                            .readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                            .followRedirects(true)
                            .followSslRedirects(true)
                            .retryOnConnectionFailure(false)
                            .build();
                }
            }
        }
        return client;
    }

    /** 同步搜索单个站点，返回解析后的影片列表（失败或取消返回空列表） */
    public static List<Movie.Video> searchSite(SourceBean sourceBean, String keyword, AtomicBoolean cancelled) {
        if (sourceBean == null || TextUtils.isEmpty(keyword) || isCancelled(cancelled)) {
            return Collections.emptyList();
        }
        int type = sourceBean.getType();
        try {
            if (type == 3) {
                Spider sp = ApiConfig.get().getCSP(sourceBean);
                if (sp == null || isCancelled(cancelled)) return Collections.emptyList();
                String json = sp.searchContent(keyword, false);
                AbsXml data = parseJson(json, sourceBean.getKey());
                return extractVideos(data);
            } else if (type == 0 || type == 1 || type == 4) {
                HttpUrl parsed = HttpUrl.parse(sourceBean.getApi());
                if (parsed == null) return Collections.emptyList();
                HttpUrl.Builder ub = parsed.newBuilder().addQueryParameter("wd", keyword);
                if (type == 1 || type == 4) {
                    ub.addQueryParameter("ac", "detail");
                }
                if (type == 4) {
                    ub.addQueryParameter("quick", "false");
                }
                String body = getString(ub.build(), cancelled);
                if (TextUtils.isEmpty(body)) return Collections.emptyList();
                AbsXml data = (type == 0)
                        ? parseXml(body, sourceBean.getKey())
                        : parseJson(body, sourceBean.getKey());
                return extractVideos(data);
            }
        } catch (Throwable ignored) {
        }
        return Collections.emptyList();
    }

    /**
     * 确保拿到包含播放线路的完整 {@link VodInfo}。
     * 若搜索返回的 {@code video} 已经带了 {@code urlBean.infoList}，直接就地组装，零额外请求；
     * 否则同步拉一次单站详情接口。
     */
    public static VodInfo ensureDetail(Movie.Video video, AtomicBoolean cancelled) {
        if (video == null || TextUtils.isEmpty(video.sourceKey) || isCancelled(cancelled)) {
            return null;
        }
        if (hasPlayableData(video)) {
            return buildVodInfo(video, video.sourceKey);
        }
        SourceBean sourceBean = ApiConfig.get().getSource(video.sourceKey);
        if (sourceBean == null || TextUtils.isEmpty(video.id)) {
            return null;
        }
        Movie.Video full = fetchDetailVideo(sourceBean, video.id, cancelled);
        if (full == null) return null;
        if (TextUtils.isEmpty(full.id)) full.id = video.id;
        if (TextUtils.isEmpty(full.name)) full.name = video.name;
        if (TextUtils.isEmpty(full.pic)) full.pic = video.pic;
        if (TextUtils.isEmpty(full.sourceKey)) full.sourceKey = video.sourceKey;
        return hasPlayableData(full) ? buildVodInfo(full, video.sourceKey) : null;
    }

    public static boolean hasPlayableData(Movie.Video video) {
        if (video == null || video.urlBean == null || video.urlBean.infoList == null) return false;
        for (Movie.Video.UrlBean.UrlInfo info : video.urlBean.infoList) {
            if (info != null && info.beanList != null && !info.beanList.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private static VodInfo buildVodInfo(Movie.Video video, String sourceKey) {
        VodInfo info = new VodInfo();
        info.setVideo(video);
        info.sourceKey = sourceKey;
        if (info.seriesFlags == null || info.seriesFlags.isEmpty()
                || info.seriesMap == null || info.seriesMap.isEmpty()) {
            return null;
        }
        LineOrder.apply(info, sourceKey);
        return info;
    }

    private static Movie.Video fetchDetailVideo(SourceBean sourceBean, String vodId, AtomicBoolean cancelled) {
        if (isCancelled(cancelled)) return null;
        int type = sourceBean.getType();
        try {
            if (type == 3) {
                Spider sp = ApiConfig.get().getCSP(sourceBean);
                if (sp == null || isCancelled(cancelled)) return null;
                List<String> ids = new ArrayList<>(1);
                ids.add(vodId);
                String json = sp.detailContent(ids);
                AbsXml data = parseJson(json, sourceBean.getKey());
                List<Movie.Video> list = extractVideos(data);
                return list.isEmpty() ? null : list.get(0);
            } else if (type == 0 || type == 1 || type == 4) {
                HttpUrl parsed = HttpUrl.parse(sourceBean.getApi());
                if (parsed == null) return null;
                HttpUrl.Builder ub = parsed.newBuilder()
                        .addQueryParameter("ac", type == 0 ? "videolist" : "detail")
                        .addQueryParameter("ids", vodId);
                if (type == 4 && !TextUtils.isEmpty(sourceBean.getExt())) {
                    ub.addQueryParameter("extend", sourceBean.getExt());
                }
                String body = getString(ub.build(), cancelled);
                if (TextUtils.isEmpty(body)) return null;
                AbsXml data = (type == 0)
                        ? parseXml(body, sourceBean.getKey())
                        : parseJson(body, sourceBean.getKey());
                List<Movie.Video> list = extractVideos(data);
                return list.isEmpty() ? null : list.get(0);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static String getString(HttpUrl url, AtomicBoolean cancelled) throws Exception {
        if (isCancelled(cancelled)) return null;
        Request req = new Request.Builder()
                .url(url)
                .header("User-Agent", UA)
                .build();
        try (Response resp = http().newCall(req).execute()) {
            if (isCancelled(cancelled) || !resp.isSuccessful()) return null;
            ResponseBody body = resp.body();
            return body == null ? null : body.string();
        }
    }

    private static List<Movie.Video> extractVideos(AbsXml data) {
        if (data == null || data.movie == null || data.movie.videoList == null) {
            return Collections.emptyList();
        }
        return data.movie.videoList;
    }

    private static AbsXml parseJson(String json, String sourceKey) {
        if (TextUtils.isEmpty(json)) return null;
        try {
            AbsJson absJson = GSON.fromJson(json, AbsJson.class);
            if (absJson == null) return null;
            AbsXml data = absJson.toAbsXml();
            populateUrlBeans(data, sourceKey);
            return data;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static AbsXml parseXml(String xml, String sourceKey) {
        if (TextUtils.isEmpty(xml)) return null;
        try {
            XStream xstream = new XStream(new DomDriver());
            xstream.autodetectAnnotations(true);
            xstream.processAnnotations(AbsXml.class);
            xstream.ignoreUnknownElements();
            xstream.allowTypes(new Class[]{AbsXml.class, Movie.class, Movie.Video.class,
                    Movie.Video.UrlBean.class, Movie.Video.UrlBean.UrlInfo.class,
                    Movie.Video.UrlBean.UrlInfo.InfoBean.class});
            AbsXml data = (AbsXml) xstream.fromXML(xml);
            populateUrlBeans(data, sourceKey);
            return data;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void populateUrlBeans(AbsXml data, String sourceKey) {
        if (data == null || data.movie == null || data.movie.videoList == null) return;
        for (Movie.Video video : data.movie.videoList) {
            if (video.urlBean != null && video.urlBean.infoList != null) {
                for (Movie.Video.UrlBean.UrlInfo urlInfo : video.urlBean.infoList) {
                    if (urlInfo == null || urlInfo.urls == null) continue;
                    String[] parts = urlInfo.urls.contains("#")
                            ? urlInfo.urls.split("#")
                            : new String[]{urlInfo.urls};
                    List<Movie.Video.UrlBean.UrlInfo.InfoBean> list = new ArrayList<>();
                    for (String s : parts) {
                        if (TextUtils.isEmpty(s)) continue;
                        String[] ss = s.split("\\$");
                        if (ss.length >= 2) {
                            list.add(new Movie.Video.UrlBean.UrlInfo.InfoBean(ss[0], ss[1]));
                        } else if (ss.length == 1) {
                            list.add(new Movie.Video.UrlBean.UrlInfo.InfoBean(String.valueOf(list.size() + 1), ss[0]));
                        }
                    }
                    urlInfo.beanList = list;
                }
            }
            if (TextUtils.isEmpty(video.sourceKey)) {
                video.sourceKey = sourceKey;
            }
        }
    }

    private static boolean isCancelled(AtomicBoolean cancelled) {
        return cancelled != null && cancelled.get();
    }
}
