package com.github.tvbox.osc.util;

import com.github.tvbox.osc.bean.VodInfo;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * 轻量级视频流可播性探测引擎：
 * 1. 对 HLS（m3u8）校验清单以 #EXTM3U 开头（主清单自动追一层子清单），并拉取首个媒体分片前 4KB 确认可达；
 * 2. 对直链媒体（mp4 / mkv / flv / ts 等）发 Range: bytes=0-4095，校验非 HTML 错误页且可读到有效字节；
 * 3. 对非直链（需解析 / 嗅探）返回 UNVERIFIABLE，不误杀但优先级低于实测可播直链；
 * 4. 内置 10 分钟内存结果缓存，避免同一条地址在搜索/详情跳转间重复发网络请求。
 */
public final class StreamProbe {

    public enum Status {
        PLAYABLE,
        FAILED,
        UNVERIFIABLE
    }

    public static final class Result {
        public final Status status;
        public final long latencyMs;
        public final String reason;
        public final String matchedFlag;

        public Result(Status status, long latencyMs, String reason, String matchedFlag) {
            this.status = status;
            this.latencyMs = latencyMs;
            this.reason = reason;
            this.matchedFlag = matchedFlag;
        }

        public boolean isPlayable() {
            return status == Status.PLAYABLE;
        }

        public Result withFlag(String flag) {
            return new Result(status, latencyMs, reason, flag);
        }
    }

    private static final class CacheEntry {
        final Result result;
        final long expireAt;

        CacheEntry(Result result, long expireAt) {
            this.result = result;
            this.expireAt = expireAt;
        }
    }

    private static final String DEFAULT_UA = "okHttp/Mod-1.5.0.0";
    private static final int CONNECT_TIMEOUT_MS = 2200;
    private static final int READ_TIMEOUT_MS = 2800;
    private static final int MAX_MANIFEST_BYTES = 256 * 1024;
    private static final int PROBE_SEGMENT_BYTES = 4096;
    private static final long CACHE_TTL_PLAYABLE_MS = 10L * 60 * 1000;
    private static final long CACHE_TTL_FAILED_MS = 3L * 60 * 1000;
    private static final int MAX_CACHE_SIZE = 500;

    private static final ConcurrentHashMap<String, CacheEntry> CACHE = new ConcurrentHashMap<>();

    private static volatile OkHttpClient client;

    private StreamProbe() {
    }

    private static OkHttpClient httpClient() {
        if (client == null) {
            synchronized (StreamProbe.class) {
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

    /** 判断 URL 是否是可直接探测的 HTTP(S) 媒体直链 */
    public static boolean isDirectStreamUrl(String url) {
        if (url == null) return false;
        String u = url.trim();
        if (!u.startsWith("http://") && !u.startsWith("https://")) return false;
        String lower = stripQueryAndFragment(u).toLowerCase(Locale.ROOT);
        if (lower.endsWith(".m3u8") || lower.endsWith(".m3u")
                || lower.endsWith(".mp4") || lower.endsWith(".mkv")
                || lower.endsWith(".flv") || lower.endsWith(".ts")
                || lower.endsWith(".webm") || lower.endsWith(".mov")) {
            return true;
        }
        return u.toLowerCase(Locale.ROOT).contains(".m3u8");
    }

    public static boolean isHlsUrl(String url) {
        if (url == null) return false;
        String lower = url.trim().toLowerCase(Locale.ROOT);
        String clean = stripQueryAndFragment(lower);
        return clean.endsWith(".m3u8") || clean.endsWith(".m3u") || lower.contains(".m3u8");
    }

    /** 探测单条播放 URL 的真实可播性 */
    public static Result probeUrl(String url, Map<String, String> headers, AtomicBoolean cancelled) {
        if (cancelled != null && cancelled.get()) {
            return new Result(Status.FAILED, 0, "cancelled", null);
        }
        if (url == null || url.trim().isEmpty()) {
            return new Result(Status.FAILED, 0, "empty url", null);
        }
        String trimmed = url.trim();
        if (!isDirectStreamUrl(trimmed)) {
            return new Result(Status.UNVERIFIABLE, 0, "not direct stream", null);
        }

        long now = System.currentTimeMillis();
        CacheEntry cached = CACHE.get(trimmed);
        if (cached != null && cached.expireAt > now) {
            return cached.result;
        }

        long start = System.currentTimeMillis();
        Result res = isHlsUrl(trimmed)
                ? probeHls(trimmed, headers, cancelled, start)
                : probeDirectBinary(trimmed, headers, cancelled, start);

        if (cancelled == null || !cancelled.get()) {
            putCache(trimmed, res);
        }
        return res;
    }

    /**
     * 探测一部影片的线路列表（应先经 LineOrder.apply 排序）。
     * 最多顺次探测 maxLinesToTry 条直链线路，一旦有线路测通立即返回 PLAYABLE + 命中线路名。
     */
    public static Result probeVod(VodInfo vodInfo, String sourceKey, int maxLinesToTry, AtomicBoolean cancelled) {
        if (vodInfo == null || vodInfo.seriesFlags == null || vodInfo.seriesMap == null
                || vodInfo.seriesFlags.isEmpty()) {
            return new Result(Status.FAILED, 0, "empty playlist", null);
        }
        int tried = 0;
        boolean sawUnverifiable = false;
        String firstUnverifiableFlag = null;
        Result lastFailure = null;

        for (VodInfo.VodSeriesFlag flag : vodInfo.seriesFlags) {
            if (cancelled != null && cancelled.get()) {
                return new Result(Status.FAILED, 0, "cancelled", null);
            }
            List<VodInfo.VodSeries> list = vodInfo.seriesMap.get(flag.name);
            if (list == null || list.isEmpty()) continue;
            String url = list.get(0).url;
            if (!isDirectStreamUrl(url)) {
                if (!sawUnverifiable) {
                    sawUnverifiable = true;
                    firstUnverifiableFlag = flag.name;
                }
                continue;
            }
            if (tried >= maxLinesToTry) break;
            tried++;

            Result r = probeUrl(url, null, cancelled);
            if (r.isPlayable()) {
                if (sourceKey != null) {
                    SiteQualityStore.recordProbeSuccess(sourceKey, r.latencyMs);
                    SiteQualityStore.recordProbeSuccess(SiteQualityStore.lineKey(sourceKey, flag.name), r.latencyMs);
                }
                return r.withFlag(flag.name);
            } else if (r.status == Status.FAILED && (cancelled == null || !cancelled.get())) {
                lastFailure = r;
                if (sourceKey != null) {
                    SiteQualityStore.recordProbeFailure(SiteQualityStore.lineKey(sourceKey, flag.name));
                }
            }
        }

        if (tried > 0 && !sawUnverifiable) {
            if (sourceKey != null && (cancelled == null || !cancelled.get())) {
                SiteQualityStore.recordProbeFailure(sourceKey);
            }
            return lastFailure != null ? lastFailure : new Result(Status.FAILED, 0, "all direct lines failed", null);
        }
        if (sawUnverifiable) {
            return new Result(Status.UNVERIFIABLE, 0, "needs parser", firstUnverifiableFlag);
        }
        return new Result(Status.FAILED, 0, "no playable line", null);
    }

    private static Result probeHls(String url, Map<String, String> headers, AtomicBoolean cancelled, long startMs) {
        try {
            FetchResult manifest = fetchBytes(url, headers, MAX_MANIFEST_BYTES, false, cancelled);
            if (manifest == null) {
                return new Result(Status.FAILED, System.currentTimeMillis() - startMs, "manifest unreachable", null);
            }
            String text = stripBom(new String(manifest.bytes, StandardCharsets.UTF_8));
            if (!text.startsWith("#EXTM3U")) {
                return new Result(Status.FAILED, System.currentTimeMillis() - startMs, "not m3u8", null);
            }
            String mediaText = text;
            String mediaBase = manifest.finalUrl;

            // 主清单（Master Playlist）再追一层到第一个子清单
            if (isMasterPlaylist(text)) {
                String subUri = extractFirstMediaUri(text, manifest.finalUrl);
                if (subUri == null) {
                    return new Result(Status.FAILED, System.currentTimeMillis() - startMs, "empty master playlist", null);
                }
                FetchResult sub = fetchBytes(subUri, headers, MAX_MANIFEST_BYTES, false, cancelled);
                if (sub == null) {
                    return new Result(Status.FAILED, System.currentTimeMillis() - startMs, "sub-playlist unreachable", null);
                }
                mediaText = stripBom(new String(sub.bytes, StandardCharsets.UTF_8));
                mediaBase = sub.finalUrl;
                if (!mediaText.startsWith("#EXTM3U")) {
                    return new Result(Status.FAILED, System.currentTimeMillis() - startMs, "sub-playlist invalid", null);
                }
            }

            String segUri = extractFirstMediaUri(mediaText, mediaBase);
            if (segUri == null) {
                return new Result(Status.FAILED, System.currentTimeMillis() - startMs, "no segment in m3u8", null);
            }
            FetchResult seg = fetchBytes(segUri, headers, PROBE_SEGMENT_BYTES, true, cancelled);
            if (seg == null || seg.bytes.length == 0) {
                return new Result(Status.FAILED, System.currentTimeMillis() - startMs, "segment unreachable", null);
            }
            if (looksLikeHtmlPage(seg.contentType, seg.bytes)) {
                return new Result(Status.FAILED, System.currentTimeMillis() - startMs, "segment is html", null);
            }
            return new Result(Status.PLAYABLE, System.currentTimeMillis() - startMs, "ok", null);
        } catch (Throwable t) {
            return new Result(Status.FAILED, System.currentTimeMillis() - startMs, t.getClass().getSimpleName(), null);
        }
    }

    private static Result probeDirectBinary(String url, Map<String, String> headers, AtomicBoolean cancelled, long startMs) {
        try {
            FetchResult data = fetchBytes(url, headers, PROBE_SEGMENT_BYTES, true, cancelled);
            if (data == null || data.bytes.length == 0) {
                return new Result(Status.FAILED, System.currentTimeMillis() - startMs, "stream unreachable", null);
            }
            if (looksLikeHtmlPage(data.contentType, data.bytes)) {
                return new Result(Status.FAILED, System.currentTimeMillis() - startMs, "html response", null);
            }
            return new Result(Status.PLAYABLE, System.currentTimeMillis() - startMs, "ok", null);
        } catch (Throwable t) {
            return new Result(Status.FAILED, System.currentTimeMillis() - startMs, t.getClass().getSimpleName(), null);
        }
    }

    private static final class FetchResult {
        final String finalUrl;
        final String contentType;
        final byte[] bytes;

        FetchResult(String finalUrl, String contentType, byte[] bytes) {
            this.finalUrl = finalUrl;
            this.contentType = contentType;
            this.bytes = bytes;
        }
    }

    private static FetchResult fetchBytes(String url, Map<String, String> headers, int maxBytes,
                                          boolean useRange, AtomicBoolean cancelled) throws IOException {
        if (cancelled != null && cancelled.get()) return null;
        Request.Builder builder = new Request.Builder()
                .url(url)
                .header("User-Agent", DEFAULT_UA);
        if (useRange) {
            builder.header("Range", "bytes=0-" + (maxBytes - 1));
        }
        if (headers != null) {
            for (Map.Entry<String, String> e : headers.entrySet()) {
                if (e.getKey() != null && e.getValue() != null) {
                    builder.header(e.getKey(), e.getValue());
                }
            }
        }
        Call call = httpClient().newCall(builder.build());
        try (Response resp = call.execute()) {
            if (cancelled != null && cancelled.get()) return null;
            int code = resp.code();
            if (code < 200 || code >= 300) return null;
            ResponseBody body = resp.body();
            if (body == null) return null;
            byte[] bytes = readUpTo(body.byteStream(), maxBytes, cancelled);
            String finalUrl = resp.request().url().toString();
            String ct = resp.header("Content-Type", "");
            return new FetchResult(finalUrl, ct, bytes);
        }
    }

    private static byte[] readUpTo(InputStream is, int maxBytes, AtomicBoolean cancelled) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(Math.min(maxBytes, 8192));
        byte[] buf = new byte[4096];
        int remaining = maxBytes;
        while (remaining > 0) {
            if (cancelled != null && cancelled.get()) break;
            int n = is.read(buf, 0, Math.min(buf.length, remaining));
            if (n <= 0) break;
            bos.write(buf, 0, n);
            remaining -= n;
        }
        return bos.toByteArray();
    }

    // ---- 纯逻辑工具方法（直接由单元测试覆盖） ----

    static boolean isMasterPlaylist(String text) {
        return text != null && text.contains("#EXT-X-STREAM-INF");
    }

    static String stripBom(String text) {
        if (text == null) return "";
        String s = text;
        if (s.startsWith("\uFEFF")) s = s.substring(1);
        return s.trim();
    }

    /** 从 m3u8 文本提取第一条非注释 URI，并按 baseUrl 解析相对路径 */
    public static String extractFirstMediaUri(String playlistText, String baseUrl) {
        if (playlistText == null || playlistText.isEmpty()) return null;
        String[] lines = playlistText.split("\\r?\\n");
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            return resolveUrl(baseUrl, line);
        }
        return null;
    }

    static String resolveUrl(String baseUrl, String target) {
        if (target == null || target.isEmpty()) return null;
        if (target.startsWith("http://") || target.startsWith("https://")) return target;
        if (baseUrl == null || baseUrl.isEmpty()) return target;
        try {
            return URI.create(baseUrl).resolve(target).toString();
        } catch (Throwable ignored) {
            return target;
        }
    }

    static boolean looksLikeHtmlPage(String contentType, byte[] bytes) {
        if (contentType != null && contentType.toLowerCase(Locale.ROOT).contains("text/html")) {
            return true;
        }
        if (bytes == null || bytes.length < 5) return false;
        int len = Math.min(bytes.length, 64);
        String head = new String(bytes, 0, len, StandardCharsets.UTF_8).trim().toLowerCase(Locale.ROOT);
        return head.startsWith("<!doctype html") || head.startsWith("<html") || head.startsWith("<head");
    }

    private static String stripQueryAndFragment(String url) {
        int q = url.indexOf('?');
        int h = url.indexOf('#');
        int cut = -1;
        if (q >= 0 && h >= 0) cut = Math.min(q, h);
        else if (q >= 0) cut = q;
        else if (h >= 0) cut = h;
        return cut >= 0 ? url.substring(0, cut) : url;
    }

    private static void putCache(String url, Result res) {
        if (CACHE.size() >= MAX_CACHE_SIZE) {
            CACHE.clear();
        }
        long ttl = res.isPlayable() ? CACHE_TTL_PLAYABLE_MS : CACHE_TTL_FAILED_MS;
        CACHE.put(url, new CacheEntry(res, System.currentTimeMillis() + ttl));
    }

    public static void clearCache() {
        CACHE.clear();
    }
}
