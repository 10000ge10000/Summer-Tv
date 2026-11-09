package com.github.tvbox.osc.live;

import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import com.github.catvod.net.OkHttp;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.base.App;
import com.github.tvbox.osc.bean.LiveChannelGroup;
import com.github.tvbox.osc.util.LOG;
import com.github.tvbox.osc.util.LiveSourceMemory;
import com.github.tvbox.osc.util.MD5;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.Headers;
import okhttp3.Response;

/**
 * 直播频道列表的唯一数据来源。
 *
 * <p>来源按优先级合并：
 * <ol>
 *   <li>用户维护的 iptv-api 结果（冷启动远程刷新后的磁盘缓存，没有缓存时用打包的 user_result）；</li>
 *   <li>打包的 verified_live_fallback；</li>
 *   <li>打包的 live_curated（经 tools/live-check 检测的公开免费源）；</li>
 *   <li>配置 JSON 自带的 lives：内联列表直接合并；地址形式的下载后合并，带磁盘缓存，失败时只用内置源。</li>
 * </ol>
 * 解析在后台线程完成，结果通过回调交给主线程。远程刷新或配置变化只把列表标记为过期，
 * 下次进入直播时重新加载，不会改动正在播放的列表。
 *
 * <p>直播源固化，不读取用户自定义的直播地址（HawkConfig.LIVE_URL）。
 */
public final class LiveRepository {

    public interface Callback {
        /** 主线程回调；列表可能为空（所有来源都不可用） */
        void onLoaded(List<LiveChannelGroup> groups);
    }

    /** 冷启动刷新写入的缓存文件名（与 ApiConfig 一致） */
    public static final String REMOTE_LIVE_CACHE_FILE = "user_result.m3u";
    private static final String CONFIG_CACHE_PREFIX = "live_config_";
    private static final int CONFIG_DOWNLOAD_TIMEOUT_MS = 10_000;

    private static final LiveRepository INSTANCE = new LiveRepository();

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final List<Callback> pending = new ArrayList<>();
    private volatile List<LiveChannelGroup> groups = Collections.emptyList();
    private volatile boolean stale = true;
    private boolean loading = false;

    /** 配置 JSON 自带的直播源 */
    private volatile JsonArray configInlineLives;
    private volatile String configLiveUrl;

    private LiveRepository() {
    }

    public static LiveRepository get() {
        return INSTANCE;
    }

    /** 当前已加载的列表（可能为空或已过期），供 ApiConfig.getChannelGroupList() 兼容使用 */
    public List<LiveChannelGroup> getGroups() {
        return groups;
    }

    /** 下次 load 时重新解析 */
    public void markStale() {
        stale = true;
    }

    /**
     * 记录配置 JSON 自带的直播源。
     *
     * @param inlineLives 原生 lives 列表（group / channels / urls），没有时传 null
     * @param liveUrl     txt / m3u 订阅地址，没有时传 null
     */
    public void setConfigLives(JsonArray inlineLives, String liveUrl) {
        String url = liveUrl == null || liveUrl.trim().isEmpty() ? null : liveUrl.trim();
        boolean changed = !equalsNullable(url, configLiveUrl)
                || !equalsNullable(inlineLives == null ? null : inlineLives.toString(),
                configInlineLives == null ? null : configInlineLives.toString());
        configInlineLives = inlineLives;
        configLiveUrl = url;
        if (changed) stale = true;
    }

    /** 列表新鲜时立即回调，否则在后台重新加载后回调；并发调用会合并为一次加载 */
    public void load(final Callback callback) {
        synchronized (pending) {
            if (!stale && !groups.isEmpty()) {
                final List<LiveChannelGroup> snapshot = groups;
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        callback.onLoaded(snapshot);
                    }
                });
                return;
            }
            pending.add(callback);
            if (loading) return;
            loading = true;
            stale = false;
        }
        executor.execute(new Runnable() {
            @Override
            public void run() {
                List<LiveChannelGroup> result;
                try {
                    result = buildGroups();
                } catch (Throwable th) {
                    LOG.e("Live list load failed: " + th.getMessage());
                    result = Collections.emptyList();
                }
                final List<LiveChannelGroup> loaded = result;
                final List<Callback> callbacks;
                synchronized (pending) {
                    if (!loaded.isEmpty()) groups = loaded;
                    else stale = true;
                    callbacks = new ArrayList<>(pending);
                    pending.clear();
                    loading = false;
                }
                final List<LiveChannelGroup> delivered = loaded.isEmpty() ? groups : loaded;
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        for (Callback cb : callbacks) cb.onLoaded(delivered);
                    }
                });
            }
        });
    }

    /** 后台线程：读取并合并所有来源 */
    private List<LiveChannelGroup> buildGroups() {
        List<LiveSourceParser.Entry> entries = new ArrayList<>();
        appendEntries(entries, "remote", readRemoteLive(), false);
        appendEntries(entries, "verified", readRaw(R.raw.verified_live_fallback), false);
        appendEntries(entries, "curated", readRaw(R.raw.live_curated), false);
        JsonArray inline = configInlineLives;
        if (inline != null) entries.addAll(parseInlineLives(inline));
        String url = configLiveUrl;
        if (url != null) appendEntries(entries, "config", readConfigLive(url), true);

        String lineFormat = App.getInstance().getString(R.string.live_line_default_name);
        List<LiveChannelGroup> result = LiveGroupNormalizer.build(entries, lineFormat);
        // 先换算旧版按下标的线路记忆（下标对应来源顺序），再排序线路
        LiveSourceMemory.migrateLegacy(result);
        LiveGroupNormalizer.rankLines(result, new LiveGroupNormalizer.LineRanker() {
            @Override
            public String preferredUrl(String channelKey) {
                return LiveSourceMemory.preferredUrl(channelKey);
            }

            @Override
            public long latencyMs(String url) {
                return LiveSourceMemory.latency(url);
            }
        });
        LiveSourceMemory.applyTo(result);
        LiveMigration.migrateChannelScales(result);
        LOG.i("Live list loaded: entries=" + entries.size() + ", groups=" + result.size()
                + ", channels=" + LiveGroupNormalizer.countChannels(result));
        return result;
    }

    private void appendEntries(List<LiveSourceParser.Entry> target, String sourceName, String content, boolean allowPassword) {
        if (content == null || content.isEmpty()) return;
        int before = target.size();
        for (LiveSourceParser.Entry entry : LiveSourceParser.parse(content)) {
            if (isRevokedUrl(entry.url)) continue;
            if (allowPassword) splitGroupPassword(entry);
            target.add(entry);
        }
        LOG.i("Live source " + sourceName + ": entries=" + (target.size() - before));
    }

    /** 配置自带源沿用 TVBox 约定：分组名「名称_密码」 */
    private static void splitGroupPassword(LiveSourceParser.Entry entry) {
        if (entry.group == null) return;
        String[] split = entry.group.split("_", 2);
        if (split.length == 2 && !split[1].isEmpty()) {
            entry.group = split[0];
            entry.password = split[1];
        }
    }

    /** 原生 lives 格式：[{"group":"..","channels":[{"name":"..","urls":["url$线路名"]}]}] */
    private List<LiveSourceParser.Entry> parseInlineLives(JsonArray lives) {
        List<LiveSourceParser.Entry> result = new ArrayList<>();
        for (JsonElement groupElement : lives) {
            if (!groupElement.isJsonObject()) continue;
            JsonObject groupObj = groupElement.getAsJsonObject();
            if (!groupObj.has("channels") || !groupObj.get("channels").isJsonArray()) continue;
            String group = groupObj.has("group") ? groupObj.get("group").getAsString().trim() : LiveSourceParser.UNGROUPED;
            for (JsonElement channelElement : groupObj.getAsJsonArray("channels")) {
                if (!channelElement.isJsonObject()) continue;
                JsonObject channel = channelElement.getAsJsonObject();
                String name = channel.has("name") ? channel.get("name").getAsString().trim() : "";
                if (!channel.has("urls") || !channel.get("urls").isJsonArray()) continue;
                for (JsonElement urlElement : channel.getAsJsonArray("urls")) {
                    if (!urlElement.isJsonPrimitive()) continue;
                    LiveSourceParser.Entry entry = new LiveSourceParser.Entry();
                    entry.group = group;
                    entry.name = name;
                    if (!LiveSourceParser.applyUrl(entry, urlElement.getAsString())) continue;
                    if (isRevokedUrl(entry.url)) continue;
                    splitGroupPassword(entry);
                    result.add(entry);
                }
            }
        }
        LOG.i("Live source config-inline: entries=" + result.size());
        return result;
    }

    // ---------------- 读取各来源 ----------------

    private String readRemoteLive() {
        File cache = new File(App.getInstance().getFilesDir(), REMOTE_LIVE_CACHE_FILE);
        if (cache.isFile() && cache.length() > 0) {
            try {
                return readFile(cache);
            } catch (IOException e) {
                LOG.e("Read remote live cache failed: " + e.getMessage());
            }
        }
        return readRaw(R.raw.user_result);
    }

    /**
     * 配置自带的订阅地址：有缓存先用缓存，并在后台刷新缓存（下次进入生效）；
     * 没有缓存时同步下载一次，失败则只用内置源。
     */
    private String readConfigLive(final String url) {
        final File cache = new File(App.getInstance().getFilesDir(), CONFIG_CACHE_PREFIX + MD5.string2MD5(url) + ".txt");
        if (cache.isFile() && cache.length() > 0) {
            try {
                String content = readFile(cache);
                executor.execute(new Runnable() {
                    @Override
                    public void run() {
                        String fresh = download(url);
                        if (fresh != null && writeCache(cache, fresh)) markStale();
                    }
                });
                return content;
            } catch (IOException e) {
                LOG.e("Read config live cache failed: " + e.getMessage());
            }
        }
        String content = download(url);
        if (content != null) writeCache(cache, content);
        return content;
    }

    private String download(String url) {
        if (!url.startsWith("http")) return null;
        try (Response response = OkHttp.newCall(OkHttp.client(CONFIG_DOWNLOAD_TIMEOUT_MS), url,
                Headers.of("User-Agent", App.DEFAULT_HTTP_UA)).execute()) {
            if (!response.isSuccessful() || response.body() == null) return null;
            String body = response.body().string();
            return LiveSourceParser.parse(body).isEmpty() ? null : body;
        } catch (Throwable th) {
            LOG.e("Download config live failed: " + th.getMessage());
            return null;
        }
    }

    private static boolean writeCache(File cache, String content) {
        File temp = new File(cache.getPath() + ".tmp");
        try (FileOutputStream fos = new FileOutputStream(temp)) {
            fos.write(content.getBytes("UTF-8"));
            fos.flush();
        } catch (Throwable th) {
            temp.delete();
            return false;
        }
        try {
            if (cache.isFile() && readFile(cache).equals(content)) {
                temp.delete();
                return false;
            }
        } catch (IOException ignored) {
        }
        if (cache.exists() && !cache.delete()) {
            temp.delete();
            return false;
        }
        return temp.renameTo(cache);
    }

    private static String readRaw(int rawResId) {
        try (InputStream in = App.getInstance().getResources().openRawResource(rawResId)) {
            return readStream(in);
        } catch (Throwable th) {
            LOG.e("Read raw live failed: " + th.getMessage());
            return "";
        }
    }

    private static String readFile(File file) throws IOException {
        try (InputStream in = new FileInputStream(file)) {
            return readStream(in);
        }
    }

    private static String readStream(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, "UTF-8"))) {
            String line;
            while ((line = reader.readLine()) != null) sb.append(line).append('\n');
        }
        return sb.toString();
    }

    private static boolean isRevokedUrl(String url) {
        try {
            return App.REVOKED_LIVE_HOST.equalsIgnoreCase(Uri.parse(url).getHost());
        } catch (Throwable th) {
            return false;
        }
    }

    private static boolean equalsNullable(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }
}
