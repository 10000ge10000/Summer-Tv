package com.github.tvbox.osc.util;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.widget.TextView;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.bean.Movie;
import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.bean.VodInfo;
import com.github.tvbox.osc.cache.RoomDataManger;
import com.github.tvbox.osc.ui.activity.DetailActivity;
import com.github.tvbox.osc.ui.activity.FastSearchActivity;
import com.github.tvbox.osc.ui.activity.PlayActivity;
import com.github.tvbox.osc.ui.dialog.BaseDialog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 单击海报智能优选可播源直达器：
 * 1. 后台按站点质量分顺序并发搜索（跳过短期熔断的失效站）；
 * 2. 对匹配结果流式轻探首集直链可播性（First-Pass 竞速）；
 * 3. 命中第一个实测可播源后：
 *    - 单集电影：直接全屏进入 {@link PlayActivity} 起播；
 *    - 多集剧集 / 综艺 / 动漫：秒开已预加载优选线路的 {@link DetailActivity}；
 *    - 同名其余站点打包进 {@link PlaybackCandidates}，供播中异常时无感自动换源；
 * 4. 超时或全站无直链测通时自动降级进入 {@link FastSearchActivity}，不死等不报错。
 */
public final class SmartSourceResolver {

    /** 详情页可直接消费内存缓存的预加载 VodInfo，避免重复网络请求 */
    public static final String EXTRA_PRELOADED_TOKEN = "preloadedVodToken";
    public static final String EXTRA_PREFERRED_FLAG = "preferredFlag";

    private static final long TOTAL_TIMEOUT_MS = 5500L;
    private static final long PARTIAL_MATCH_ACCEPT_MS = 3200L;
    private static final int SEARCH_THREADS = 6;
    private static final int PROBE_THREADS = 4;
    private static final int MAX_CANDIDATES_TO_PROBE = 14;

    private static final ConcurrentHashMap<String, VodInfo> PRELOADED_CACHE = new ConcurrentHashMap<>();
    private static long preloadSeq = 0;

    private static final class ResolvedHit {
        final Movie.Video video;
        final VodInfo vodInfo;
        final String matchedFlag;
        final int titleMatch;
        final long latencyMs;

        ResolvedHit(Movie.Video video, VodInfo vodInfo, String matchedFlag, int titleMatch, long latencyMs) {
            this.video = video;
            this.vodInfo = vodInfo;
            this.matchedFlag = matchedFlag;
            this.titleMatch = titleMatch;
            this.latencyMs = latencyMs;
        }
    }

    private SmartSourceResolver() {
    }

    public static String putPreloadedVod(VodInfo info) {
        if (info == null) return null;
        if (PRELOADED_CACHE.size() > 8) PRELOADED_CACHE.clear();
        String token = "pv" + (++preloadSeq);
        PRELOADED_CACHE.put(token, info);
        return token;
    }

    public static VodInfo takePreloadedVod(String token) {
        if (token == null) return null;
        return PRELOADED_CACHE.remove(token);
    }

    /** 直接打开聚合搜索页（供长按海报手动选源或兜底降级使用） */
    public static void openManualSearch(Activity activity, String title) {
        if (activity == null || activity.isFinishing() || TextUtils.isEmpty(title)) return;
        Bundle bundle = new Bundle();
        bundle.putString("title", title.trim());
        Intent intent = new Intent(activity, FastSearchActivity.class);
        intent.putExtras(bundle);
        activity.startActivity(intent);
    }

    /**
     * 首页“继续观看”或已有明确有效站点的记录：单集或历史续播直接全屏，否则进详情页。
     */
    public static void openDirectRecord(Activity activity, String sourceKey, String vodId, boolean directPlayIfHistory) {
        if (activity == null || activity.isFinishing() || TextUtils.isEmpty(sourceKey) || TextUtils.isEmpty(vodId)) {
            return;
        }
        if (directPlayIfHistory) {
            VodInfo saved = RoomDataManger.getVodInfo(sourceKey, vodId);
            if (saved != null && saved.seriesMap != null && !saved.seriesMap.isEmpty()) {
                if (TextUtils.isEmpty(saved.playFlag) || !saved.seriesMap.containsKey(saved.playFlag)) {
                    saved.playFlag = saved.seriesMap.keySet().iterator().next();
                }
                List<VodInfo.VodSeries> list = saved.seriesMap.get(saved.playFlag);
                if (list != null && !list.isEmpty()) {
                    Bundle bundle = new Bundle();
                    bundle.putString("sourceKey", sourceKey);
                    bundle.putSerializable("VodInfo", saved);
                    Intent intent = new Intent(activity, PlayActivity.class);
                    intent.putExtras(bundle);
                    activity.startActivity(intent);
                    return;
                }
            }
        }
        Bundle bundle = new Bundle();
        bundle.putString("id", vodId);
        bundle.putString("sourceKey", sourceKey);
        Intent intent = new Intent(activity, DetailActivity.class);
        intent.putExtras(bundle);
        activity.startActivity(intent);
    }

    /**
     * 单击海报启动无感优选可播源流程。
     */
    public static void resolveAndOpen(Activity activity, String rawTitle) {
        if (activity == null || activity.isFinishing() || TextUtils.isEmpty(rawTitle)) return;
        final String title = rawTitle.trim();
        final List<SourceBean> sites = pickSearchSites();
        if (sites.isEmpty()) {
            openManualSearch(activity, title);
            return;
        }

        final Handler mainHandler = new Handler(Looper.getMainLooper());
        final AtomicBoolean finished = new AtomicBoolean(false);
        final AtomicBoolean cancelled = new AtomicBoolean(false);
        final ExecutorService searchPool = Executors.newFixedThreadPool(SEARCH_THREADS);
        final ExecutorService probePool = Executors.newFixedThreadPool(PROBE_THREADS);

        final BaseDialog dialog = new BaseDialog(activity);
        dialog.setContentView(R.layout.dialog_smart_resolve);
        dialog.setCancelable(true);
        dialog.setCanceledOnTouchOutside(false);
        TextView tvTitle = dialog.findViewById(R.id.tvResolveTitle);
        TextView tvStatus = dialog.findViewById(R.id.tvResolveStatus);
        if (tvTitle != null) tvTitle.setText(title);
        if (tvStatus != null) tvStatus.setText(R.string.smart_resolving);

        Runnable shutdownPools = () -> {
            try {
                searchPool.shutdownNow();
            } catch (Throwable ignored) {
            }
            try {
                probePool.shutdownNow();
            } catch (Throwable ignored) {
            }
        };

        dialog.setOnCancelListener(d -> {
            cancelled.set(true);
            finished.set(true);
            mainHandler.removeCallbacksAndMessages(null);
            shutdownPools.run();
        });
        dialog.show();

        final long startAt = SystemClock.elapsedRealtime();
        final CopyOnWriteArrayList<Movie.Video> allMatched = new CopyOnWriteArrayList<>();
        final Map<String, Long> siteElapsed = new ConcurrentHashMap<>();
        final AtomicInteger pendingSearches = new AtomicInteger(sites.size());
        final AtomicInteger activeProbes = new AtomicInteger(0);
        final AtomicInteger submittedProbes = new AtomicInteger(0);
        final ResolvedHit[] bestFallback = new ResolvedHit[1];

        final Runnable finalizeWithBestOrFallback = () -> {
            if (!finished.compareAndSet(false, true)) return;
            cancelled.set(true);
            mainHandler.removeCallbacksAndMessages(null);
            shutdownPools.run();
            mainHandler.post(() -> {
                safeDismiss(dialog);
                if (activity.isFinishing()) return;
                ResolvedHit hit = bestFallback[0];
                if (hit != null) {
                    launchResolved(activity, hit, allMatched, title, siteElapsed);
                } else {
                    openManualSearch(activity, title);
                }
            });
        };

        final Runnable checkIfAllDone = () -> {
            if (!finished.get() && pendingSearches.get() <= 0 && activeProbes.get() <= 0) {
                finalizeWithBestOrFallback.run();
            }
        };

        // 兜底总超时
        mainHandler.postDelayed(finalizeWithBestOrFallback, TOTAL_TIMEOUT_MS);
        // 超过 3.2 秒若有包含匹配测通，也直接接受，不必干等到 5.5 秒
        mainHandler.postDelayed(() -> {
            if (!finished.get() && bestFallback[0] != null) {
                finalizeWithBestOrFallback.run();
            }
        }, PARTIAL_MATCH_ACCEPT_MS);

        for (SourceBean site : sites) {
            searchPool.execute(() -> {
                if (cancelled.get() || finished.get()) {
                    pendingSearches.decrementAndGet();
                    checkIfAllDone.run();
                    return;
                }
                List<Movie.Video> found = SourceFetcher.searchSite(site, title, cancelled);
                long elapsed = SystemClock.elapsedRealtime() - startAt;
                if (found != null && !found.isEmpty() && !cancelled.get() && !finished.get()) {
                    siteElapsed.put(site.getKey(), elapsed);
                    Movie.Video bestFromSite = pickBestVideoFromSite(found, title);
                    if (bestFromSite != null) {
                        int match = SourceRanker.titleMatch(title, bestFromSite.name);
                        allMatched.add(bestFromSite);
                        if (match >= 1 && submittedProbes.incrementAndGet() <= MAX_CANDIDATES_TO_PROBE) {
                            activeProbes.incrementAndGet();
                            mainHandler.post(() -> {
                                if (!finished.get() && tvStatus != null) {
                                    tvStatus.setText(R.string.smart_probing);
                                }
                            });
                            probePool.execute(() -> {
                                try {
                                    if (cancelled.get() || finished.get()) return;
                                    VodInfo vodInfo = SourceFetcher.ensureDetail(bestFromSite, cancelled);
                                    if (vodInfo == null || cancelled.get() || finished.get()) return;
                                    StreamProbe.Result probe = StreamProbe.probeVod(vodInfo, bestFromSite.sourceKey, 2, cancelled);
                                    if (probe.isPlayable() && !cancelled.get() && !finished.get()) {
                                        ResolvedHit hit = new ResolvedHit(bestFromSite, vodInfo, probe.matchedFlag, match, probe.latencyMs);
                                        if (match == 2) {
                                            // 精确匹配 + 实测可播：立即胜出直达
                                            if (finished.compareAndSet(false, true)) {
                                                cancelled.set(true);
                                                mainHandler.removeCallbacksAndMessages(null);
                                                shutdownPools.run();
                                                mainHandler.post(() -> {
                                                    safeDismiss(dialog);
                                                    if (!activity.isFinishing()) {
                                                        launchResolved(activity, hit, allMatched, title, siteElapsed);
                                                    }
                                                });
                                            }
                                        } else {
                                            synchronized (bestFallback) {
                                                if (bestFallback[0] == null || hit.latencyMs < bestFallback[0].latencyMs) {
                                                    bestFallback[0] = hit;
                                                }
                                            }
                                        }
                                    }
                                } finally {
                                    activeProbes.decrementAndGet();
                                    checkIfAllDone.run();
                                }
                            });
                        }
                    }
                }
                pendingSearches.decrementAndGet();
                checkIfAllDone.run();
            });
        }
    }

    /**
     * 按用户策略路由：单集电影直接全屏开播，多集剧集/综艺/动漫进已预加载优选源的详情页。
     */
    private static void launchResolved(Activity activity, ResolvedHit hit, List<Movie.Video> collected,
                                       String query, Map<String, Long> siteElapsed) {
        VodInfo vodInfo = hit.vodInfo;
        String sourceKey = hit.video.sourceKey;
        if (!TextUtils.isEmpty(hit.matchedFlag) && vodInfo.seriesMap != null
                && vodInfo.seriesMap.containsKey(hit.matchedFlag)) {
            vodInfo.playFlag = hit.matchedFlag;
        } else if (vodInfo.seriesMap != null && !vodInfo.seriesMap.isEmpty()) {
            vodInfo.playFlag = vodInfo.seriesMap.keySet().iterator().next();
        }

        // 把已搜到的同名其他站点排好序写进 PlaybackCandidates，保留播放失败自动换源能力
        List<Movie.Video> ranked = SourceRanker.rank(new ArrayList<>(collected),
                SearchResultMerger.VIDEO_KEYS, query, SiteQualityStore.quality(), siteElapsed);
        Bundle bundle = new Bundle();
        SearchResultMerger.putCandidates(bundle, ranked, hit.video);

        List<VodInfo.VodSeries> seriesList = vodInfo.seriesMap == null ? null : vodInfo.seriesMap.get(vodInfo.playFlag);
        int episodeCount = seriesList == null ? 0 : seriesList.size();

        if (episodeCount == 1) {
            // 单集电影：恢复可能存在的续播进度并直接全屏开播
            vodInfo.playIndex = 0;
            vodInfo.playGroup = 0;
            vodInfo.playGroupCount = 20;
            vodInfo.playNote = seriesList.get(0).name;
            try {
                RoomDataManger.insertVodRecord(sourceKey, vodInfo);
            } catch (Throwable ignored) {
            }
            bundle.putString("sourceKey", sourceKey);
            bundle.putSerializable("VodInfo", vodInfo);
            Intent intent = new Intent(activity, PlayActivity.class);
            intent.putExtras(bundle);
            activity.startActivity(intent);
        } else {
            // 多集剧集/综艺/动漫：秒开详情页（预加载 VodInfo + 锁定已测通的优选线路）
            String preloadToken = putPreloadedVod(vodInfo);
            bundle.putString("id", hit.video.id);
            bundle.putString("sourceKey", sourceKey);
            if (preloadToken != null) bundle.putString(EXTRA_PRELOADED_TOKEN, preloadToken);
            if (!TextUtils.isEmpty(vodInfo.playFlag)) bundle.putString(EXTRA_PREFERRED_FLAG, vodInfo.playFlag);
            Intent intent = new Intent(activity, DetailActivity.class);
            intent.putExtras(bundle);
            activity.startActivity(intent);
        }
    }

    /** 选出可搜索站点：优先健康非熔断站点并按质量分降序；若全部熔断则回退全量 */
    private static List<SourceBean> pickSearchSites() {
        List<SourceBean> all = new ArrayList<>();
        List<SourceBean> healthy = new ArrayList<>();
        for (SourceBean bean : ApiConfig.get().getSourceBeanList()) {
            if (bean == null || !bean.isSearchable()) continue;
            all.add(bean);
            if (!SiteQualityStore.isCircuitOpen(bean.getKey())) {
                healthy.add(bean);
            }
        }
        List<SourceBean> target = healthy.isEmpty() ? all : healthy;
        Collections.sort(target, new Comparator<SourceBean>() {
            @Override
            public int compare(SourceBean a, SourceBean b) {
                return Double.compare(SiteQualityStore.score(b.getKey()), SiteQualityStore.score(a.getKey()));
            }
        });
        return target;
    }

    private static Movie.Video pickBestVideoFromSite(List<Movie.Video> videos, String query) {
        Movie.Video best = null;
        int bestMatch = -1;
        for (Movie.Video v : videos) {
            if (v == null || TextUtils.isEmpty(v.name)) continue;
            int m = SourceRanker.titleMatch(query, v.name);
            if (m > bestMatch) {
                bestMatch = m;
                best = v;
                if (bestMatch == 2) break;
            }
        }
        return bestMatch >= 1 ? best : null;
    }

    private static void safeDismiss(BaseDialog dialog) {
        try {
            if (dialog != null && dialog.isShowing()) {
                dialog.dismiss();
            }
        } catch (Throwable ignored) {
        }
    }
}
