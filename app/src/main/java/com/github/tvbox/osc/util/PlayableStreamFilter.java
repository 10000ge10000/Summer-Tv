package com.github.tvbox.osc.util;

import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import com.github.tvbox.osc.bean.Movie;
import com.github.tvbox.osc.bean.VodInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 搜索页 / 聚合搜索页共用的「仅看可播」后台流式探测筛选器。
 *
 * <p>默认开启仅看可播：
 * <ul>
 *     <li>收到的搜索结果全量保存在 {@code allItems}（切回“全部源”时 0 延迟秒切）；</li>
 *     <li>跳过处于短期熔断冷却的站点，最多 4 线程并发做 {@link SourceFetcher#ensureDetail} + {@link StreamProbe#probeVod}；</li>
 *     <li>测通一条即刻回调主线程流式显示，同时缓存已测通的 {@link VodInfo} 和最快线路，点击进详情页秒开；</li>
 *     <li>全部搜索与探测结束后若可播数为 0 但总结果 > 0，回调通知 UI 自动回退显示全部源，绝不留空白。</li>
 * </ul>
 */
public final class PlayableStreamFilter {

    public interface Callback {
        void onVerifiedPlayable(Movie.Video video);

        void onProgressChanged(int playableCount, int totalCount, boolean probing);

        void onAllFinished(int playableCount, int totalCount);
    }

    public static final class Preloaded {
        public final VodInfo vodInfo;
        public final String matchedFlag;

        Preloaded(VodInfo vodInfo, String matchedFlag) {
            this.vodInfo = vodInfo;
            this.matchedFlag = matchedFlag;
        }
    }

    private static final int PROBE_THREADS = 4;
    private static final int MAX_PROBES_PER_SEARCH = 36;

    private final Callback callback;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean playableOnly = true;
    private boolean searchCompleted = false;

    private final List<Movie.Video> allItems = new ArrayList<>();
    private final List<Movie.Video> verifiedItems = new ArrayList<>();
    private final Set<String> seenKeys = new HashSet<>();
    private final ConcurrentHashMap<String, Preloaded> preloadedMap = new ConcurrentHashMap<>();

    private ExecutorService probePool;
    private AtomicBoolean cancelled = new AtomicBoolean(false);
    private final AtomicInteger activeProbes = new AtomicInteger(0);
    private final AtomicInteger submittedProbes = new AtomicInteger(0);

    public PlayableStreamFilter(Callback callback) {
        this.callback = callback;
    }

    public boolean isPlayableOnly() {
        return playableOnly;
    }

    public void setPlayableOnly(boolean playableOnly) {
        this.playableOnly = playableOnly;
    }

    public boolean isProbing() {
        return !searchCompleted || activeProbes.get() > 0;
    }

    public synchronized List<Movie.Video> getAllItems() {
        return new ArrayList<>(allItems);
    }

    public synchronized List<Movie.Video> getVerifiedItems() {
        return new ArrayList<>(verifiedItems);
    }

    public synchronized int getTotalCount() {
        return allItems.size();
    }

    public synchronized int getPlayableCount() {
        return verifiedItems.size();
    }

    public Preloaded getPreloaded(Movie.Video video) {
        if (video == null) return null;
        return preloadedMap.get(itemKey(video));
    }

    /** 开始新搜索前重置全部状态与探测线程 */
    public synchronized void reset() {
        cancelPool();
        allItems.clear();
        verifiedItems.clear();
        seenKeys.clear();
        preloadedMap.clear();
        searchCompleted = false;
        activeProbes.set(0);
        submittedProbes.set(0);
        cancelled = new AtomicBoolean(false);
        probePool = Executors.newFixedThreadPool(PROBE_THREADS);
    }

    /** 提交一批新到达的搜索结果 */
    public void submit(List<Movie.Video> incoming, final String query) {
        if (incoming == null || incoming.isEmpty()) return;
        final List<Movie.Video> toProbe = new ArrayList<>();
        final int totalAfter;
        final int playableAfter;
        final AtomicBoolean token;
        final ExecutorService pool;

        synchronized (this) {
            if (cancelled.get() || probePool == null) return;
            token = cancelled;
            pool = probePool;
            for (Movie.Video v : incoming) {
                if (v == null || TextUtils.isEmpty(v.sourceKey)) continue;
                String k = itemKey(v);
                if (!seenKeys.add(k)) continue;
                allItems.add(v);
                if (!SiteQualityStore.isCircuitOpen(v.sourceKey)) {
                    toProbe.add(v);
                }
            }
            totalAfter = allItems.size();
            playableAfter = verifiedItems.size();
        }

        // 优先探测片名匹配度高 + 站点质量分高的候选
        Collections.sort(toProbe, new Comparator<Movie.Video>() {
            @Override
            public int compare(Movie.Video a, Movie.Video b) {
                int m = Integer.compare(SourceRanker.titleMatch(query, b.name), SourceRanker.titleMatch(query, a.name));
                if (m != 0) return m;
                return Double.compare(SiteQualityStore.score(b.sourceKey), SiteQualityStore.score(a.sourceKey));
            }
        });

        for (final Movie.Video video : toProbe) {
            if (token.get()) break;
            if (submittedProbes.incrementAndGet() > MAX_PROBES_PER_SEARCH) break;
            activeProbes.incrementAndGet();
            try {
                pool.execute(() -> {
                    try {
                        if (token.get()) return;
                        VodInfo info = SourceFetcher.ensureDetail(video, token);
                        if (info == null || token.get()) {
                            if (!token.get()) SiteQualityStore.recordProbeFailure(video.sourceKey);
                            return;
                        }
                        StreamProbe.Result res = StreamProbe.probeVod(info, video.sourceKey, 2, token);
                        if (res.isPlayable() && !token.get()) {
                            preloadedMap.put(itemKey(video), new Preloaded(info, res.matchedFlag));
                            int pCount;
                            int tCount;
                            synchronized (PlayableStreamFilter.this) {
                                if (token.get()) return;
                                verifiedItems.add(video);
                                pCount = verifiedItems.size();
                                tCount = allItems.size();
                            }
                            final int finalP = pCount;
                            final int finalT = tCount;
                            mainHandler.post(() -> {
                                if (token.get() || callback == null) return;
                                callback.onVerifiedPlayable(video);
                                callback.onProgressChanged(finalP, finalT, isProbing());
                            });
                        }
                    } finally {
                        int left = activeProbes.decrementAndGet();
                        if (left <= 0 && !token.get()) {
                            checkFinished(token);
                        }
                    }
                });
            } catch (Throwable rejected) {
                activeProbes.decrementAndGet();
            }
        }

        mainHandler.post(() -> {
            if (!token.get() && callback != null) {
                callback.onProgressChanged(playableAfter, totalAfter, isProbing());
            }
        });
    }

    /** 通知站点搜索阶段已全部返回或超时结束 */
    public void markSearchCompleted() {
        searchCompleted = true;
        AtomicBoolean token = cancelled;
        checkFinished(token);
    }

    private void checkFinished(final AtomicBoolean token) {
        if (!searchCompleted || activeProbes.get() > 0 || token.get()) return;
        final int p = getPlayableCount();
        final int t = getTotalCount();
        mainHandler.post(() -> {
            if (token.get() || callback == null) return;
            callback.onProgressChanged(p, t, false);
            callback.onAllFinished(p, t);
        });
    }

    public synchronized void destroy() {
        cancelPool();
        mainHandler.removeCallbacksAndMessages(null);
    }

    private void cancelPool() {
        cancelled.set(true);
        if (probePool != null) {
            try {
                probePool.shutdownNow();
            } catch (Throwable ignored) {
            }
            probePool = null;
        }
    }

    private static String itemKey(Movie.Video v) {
        return (v.sourceKey == null ? "" : v.sourceKey) + "#" + (v.id == null ? v.name : v.id);
    }
}
