package com.github.tvbox.osc.viewmodel;

import android.text.TextUtils;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;

import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.github.catvod.crawler.JsLoader;
import com.github.catvod.crawler.Spider;
import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.base.App;
import com.github.tvbox.osc.bean.AbsJson;
import com.github.tvbox.osc.bean.AbsSortJson;
import com.github.tvbox.osc.bean.AbsSortXml;
import com.github.tvbox.osc.bean.AbsXml;
import com.github.tvbox.osc.bean.Movie;
import com.github.tvbox.osc.bean.MovieSort;
import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.event.RefreshEvent;
import com.github.tvbox.osc.util.DefaultConfig;
import com.github.tvbox.osc.util.CategoryRequestUrl;
import com.github.tvbox.osc.util.CategoryOrganizer;
import com.github.tvbox.osc.util.MovieRecommendationMerger;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.LOG;
import com.github.tvbox.osc.util.MD5;
import com.github.tvbox.osc.util.thunder.Thunder;
import com.github.tvbox.osc.util.urlhttp.OkHttpUtil;
import com.github.tvbox.osc.util.FileUtils;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import com.lzy.okgo.OkGo;
import com.lzy.okgo.callback.AbsCallback;
import com.lzy.okgo.model.Response;
import com.orhanobut.hawk.Hawk;
import com.thoughtworks.xstream.XStream;
import com.thoughtworks.xstream.io.xml.DomDriver;

import org.apache.commons.lang3.BooleanUtils;
import org.greenrobot.eventbus.EventBus;
import org.json.JSONObject;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;


/**
 * @author pj567
 * @date :2020/12/18
 * @description:
 */
public class SourceViewModel extends ViewModel {
    public MutableLiveData<AbsSortXml> sortResult;
    public MutableLiveData<AbsXml> listResult;
    /** 分类列表加载失败：1=普通网络/解析失败，2=超时。空列表仍通过 listResult 表达。 */
    public MutableLiveData<Integer> listError;
    public MutableLiveData<AbsXml> searchResult;
    public MutableLiveData<AbsXml> quickSearchResult;
    public MutableLiveData<AbsXml> detailResult;
    public MutableLiveData<JSONObject> playResult;
    private ExecutorService searchExecutorService;
    public Gson gson;

    public void initExecutor() {
        if (searchExecutorService != null) {
            searchExecutorService.shutdownNow();
            searchExecutorService = null;
            JsLoader.stopAll();
        }
        searchExecutorService = Executors.newFixedThreadPool(5);
    }

    public void execute(Runnable runnable) {
        if (searchExecutorService != null) {
            searchExecutorService.execute(runnable);
        }
    }

    public List<Runnable> shutdownNow() {
        return searchExecutorService == null ? new ArrayList<>() : searchExecutorService.shutdownNow();
    }

    public void destroyExecutor() {
        if (searchExecutorService != null) {
            searchExecutorService = null;
        }
    }

    public SourceViewModel() {
        sortResult = new MutableLiveData<>();
        listResult = new MutableLiveData<>();
        listError = new MutableLiveData<>();
        searchResult = new MutableLiveData<>();
        quickSearchResult = new MutableLiveData<>();
        detailResult = new MutableLiveData<>();
        playResult = new MutableLiveData<>();
        gson=new Gson();
    }

    public static final ExecutorService spThreadPool = Executors.newSingleThreadExecutor();

    //homeContent缓存，最多存储5个sourceKey的AbsSortXml对象
    private static final Map<String, AbsSortXml> sortCache = Collections.synchronizedMap(new LinkedHashMap<String, AbsSortXml>(5, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Entry<String, AbsSortXml> eldest) {
            return size() > 5;
        }
    });
    private final AtomicLong listRequestSerial = new AtomicLong();
    private final Object listRequestTag = new Object();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private ExecutorService activeCategoryExecutor;
    private final ScheduledExecutorService categoryTimer = Executors.newSingleThreadScheduledExecutor();
    private Future<?> activeCategoryFuture;
    private ScheduledFuture<?> activeCategoryTimeout;
    // homeContent
    public void getSort(final String sourceKey) {
        LOG.i("echo--getSort-start");
        if (sourceKey == null) {
            sortResult.postValue(null);
            return;
        }

        // 优先检查缓存
        int homeRec = Hawk.get(HawkConfig.HOME_REC, 0);
        String configIdentity = ApiConfig.get().getConfigRevision() + "#"
                + Hawk.get(HawkConfig.API_URL, "") + "#"
                + String.valueOf(ApiConfig.get().getSource(sourceKey) == null ? "" : ApiConfig.get().getSource(sourceKey).getApi()) + "#"
                + String.valueOf(ApiConfig.get().getSource(sourceKey) == null ? "" : ApiConfig.get().getSource(sourceKey).getExt());
        String sortCacheKey = MD5.encode(configIdentity) + "#" + sourceKey + "#" + homeRec;
        AbsSortXml cached = sortCache.get(sortCacheKey);
        if (cached != null) {
            LOG.i("echo--getSort-cached--"+sourceKey);
            boolean shouldUseCache = (homeRec != 1) || (cached.videoList != null && !cached.videoList.isEmpty());
            if (shouldUseCache) {
                sortResult.postValue(cached);
                return;
            }
        }

        SourceBean sourceBean = ApiConfig.get().getSource(sourceKey);
        if (sourceBean == null) {
            LOG.e("getSort source not found: " + sourceKey);
            sortResult.postValue(null);
            return;
        }
        final int type = sourceBean.getType();
        if (type == 3) {
            Runnable waitResponse = new Runnable() {
                @Override
                public void run() {
                    ExecutorService executor = Executors.newSingleThreadExecutor();
                    Future<String> future = executor.submit(new Callable<String>() {
                        @Override
                        public String call() throws Exception {
                            Spider sp = ApiConfig.get().getCSP(sourceBean);
                            return sp.homeContent(true);
                        }
                    });
                    String sortJson = null;
                    try {
                        sortJson = future.get(20, TimeUnit.SECONDS);
                    } catch (TimeoutException e) {
                        e.printStackTrace();
                        future.cancel(true);
                    } catch (InterruptedException | ExecutionException e) {
                        e.printStackTrace();
                    } finally {
                        if (sortJson != null) {
                            final AbsSortXml sortXml = sortJson(sortResult, sortJson);
                            if (sortXml != null && Hawk.get(HawkConfig.HOME_REC, 0) == 1) {
                                AbsXml absXml = json(null, sortJson, sourceBean.getKey());
                                if (absXml != null && absXml.movie != null && absXml.movie.videoList != null && absXml.movie.videoList.size() > 0) {
                                    sortXml.videoList = absXml.movie.videoList;
                                    sortResult.postValue(sortXml);
                                    sortCache.put(sortCacheKey, sortXml);
                                } else {
                                    getHomeRecList(sourceBean, null, new HomeRecCallback() {
                                        @Override
                                        public void done(List<Movie.Video> videos) {
                                            sortXml.videoList = videos;
                                            sortResult.postValue(sortXml);
                                            sortCache.put(sortCacheKey, sortXml);
                                        }
                                    });
                                }
                            } else {
                                sortResult.postValue(sortXml);
                                sortCache.put(sortCacheKey, sortXml);
                            }
                        } else {
                            sortResult.postValue(null);
                        }
                        try {
                            executor.shutdown();
                        } catch (Throwable th) {
                            th.printStackTrace();
                        }
                    }
                }
            };
            spThreadPool.execute(waitResponse);
        } else if (type == 0 || type == 1) {
            OkGo.<String>get(sourceBean.getApi())
                    .tag(sourceBean.getKey() + "_sort")
                    .execute(new AbsCallback<String>() {
                        @Override
                        public String convertResponse(okhttp3.Response response) throws Throwable {
                            if (response.body() != null) {
                                return response.body().string();
                            } else {
                                throw new IllegalStateException("网络请求错误");
                            }
                        }

                        @Override
                        public void onSuccess(Response<String> response) {
                            AbsSortXml sortXml = null;
                            if (type == 0) {
                                String xml = response.body();
                                sortXml = sortXml(sortResult, xml);
                            } else if (type == 1) {
                                String json = response.body();
                                sortXml = sortJson(sortResult, json);
                            }
                            if (sortXml != null && Hawk.get(HawkConfig.HOME_REC, 0) == 1 && sortXml.list != null && sortXml.list.videoList != null && sortXml.list.videoList.size() > 0) {
                                ArrayList<String> ids = new ArrayList<>();
                                for (Movie.Video vod : sortXml.list.videoList) {
                                    ids.add(vod.id);
                                }
                                final AbsSortXml finalSortXml = sortXml;
                                getHomeRecList(sourceBean, ids, new HomeRecCallback() {
                                    @Override
                                    public void done(List<Movie.Video> videos) {
                                        finalSortXml.videoList = videos;
                                        sortResult.postValue(finalSortXml);
                                        sortCache.put(sortCacheKey, finalSortXml);
                                    }
                                });
                            } else {
                                sortResult.postValue(sortXml);
                                sortCache.put(sortCacheKey, sortXml);
                            }
                        }

                        @Override
                        public void onError(Response<String> response) {
                            super.onError(response);
                            sortResult.postValue(null);
                        }
                    });
        } else if (type == 4) {
            String extend=sourceBean.getExt();
            extend=getFixUrl(extend);
            OkGo.<String>get(sourceBean.getApi())
                    .tag(sourceBean.getKey() + "_sort")
                    .params("filter", "true")
                    .params("extend", extend)
                    .execute(new AbsCallback<String>() {
                        @Override
                        public String convertResponse(okhttp3.Response response) throws Throwable {
                            if (response.body() != null) {
                                return response.body().string();
                            } else {
                                throw new IllegalStateException("网络请求错误");
                            }
                        }

                        @Override
                        public void onSuccess(Response<String> response) {
                            String sortJson = response.body();
                            if (sortJson != null) {
                                final AbsSortXml sortXml = sortJson(sortResult, sortJson);
                                if (sortXml != null && Hawk.get(HawkConfig.HOME_REC, 0) == 1) {
                                    AbsXml absXml = json(null, sortJson, sourceBean.getKey());
                                    if (absXml != null && absXml.movie != null && absXml.movie.videoList != null && absXml.movie.videoList.size() > 0) {
                                        sortXml.videoList = absXml.movie.videoList;
                                        sortResult.postValue(sortXml);
                                        sortCache.put(sortCacheKey, sortXml);
                                    } else {
                                        getHomeRecList(sourceBean, null, new HomeRecCallback() {
                                            @Override
                                            public void done(List<Movie.Video> videos) {
                                                sortXml.videoList = videos;
                                                sortResult.postValue(sortXml);
                                                sortCache.put(sortCacheKey, sortXml);
                                            }
                                        });
                                    }
                                } else {
                                    sortResult.postValue(sortXml);
                                    sortCache.put(sortCacheKey, sortXml);
                                }
                            } else {
                                sortResult.postValue(null);
                            }
                        }

                        @Override
                        public void onError(Response<String> response) {
                            super.onError(response);
                            sortResult.postValue(null);
                        }
                    });
        } else {
            sortResult.postValue(null);
        }
    }

    // categoryContent
    public void cancelActiveListRequest() {
        listRequestSerial.incrementAndGet();
        if (activeCategoryFuture != null) activeCategoryFuture.cancel(true);
        if (activeCategoryExecutor != null) activeCategoryExecutor.shutdownNow();
        if (activeCategoryTimeout != null) activeCategoryTimeout.cancel(false);
        OkGo.getInstance().cancelTag(listRequestTag);
    }

    public void getList(MovieSort.SortData sortData, int page) {
        final long requestSerial = listRequestSerial.incrementAndGet();
        mainHandler.post(() -> {
            if (listRequestSerial.get() == requestSerial) listError.setValue(null);
        });
        if (activeCategoryFuture != null) activeCategoryFuture.cancel(true);
        if (activeCategoryExecutor != null) activeCategoryExecutor.shutdownNow();
        if (activeCategoryTimeout != null) activeCategoryTimeout.cancel(false);
        OkGo.getInstance().cancelTag(listRequestTag);
        SourceBean homeSourceBean = ApiConfig.get().getHomeSourceBean();
        if (homeSourceBean == null || sortData == null || TextUtils.isEmpty(sortData.id)) {
            postListError(requestSerial, false);
            return;
        }
        int type = homeSourceBean.getType();
        if (type == 1 && CategoryOrganizer.MOVIE_RECOMMENDATION.equals(sortData.id)) {
            if (sortData.recommendationCategoryIds == null || sortData.recommendationCategoryIds.isEmpty()) {
                postListError(requestSerial, false);
            } else {
                getMovieRecommendations(homeSourceBean, sortData.recommendationCategoryIds, page, requestSerial);
            }
        } else if (type == 3) {
            final ExecutorService requestExecutor = Executors.newSingleThreadExecutor();
            activeCategoryExecutor = requestExecutor;
            activeCategoryFuture = requestExecutor.submit(new Runnable() {
                @Override
                public void run() {
                    try {
                        Spider sp = ApiConfig.get().getCSP(homeSourceBean);
                        AbsXml data = json(null, sp.categoryContent(sortData.id, page + "", true, sortData.filterSelect), homeSourceBean.getKey());
                        postListResult(requestSerial, data);
                    } catch (Throwable th) {
                        th.printStackTrace();
                        postListError(requestSerial, th instanceof TimeoutException || th instanceof SocketTimeoutException);
                    } finally {
                        requestExecutor.shutdown();
                    }
                }
            });
            final Future<?> future = activeCategoryFuture;
            activeCategoryTimeout = categoryTimer.schedule(() -> {
                if (!future.isDone() && listRequestSerial.get() == requestSerial) {
                    future.cancel(true);
                    requestExecutor.shutdownNow();
                    postListError(requestSerial, true);
                }
            }, 20, TimeUnit.SECONDS);
        } else if (type == 0 || type == 1) {
            OkGo.<String>get(type == 1 ? CategoryRequestUrl.normalize(homeSourceBean.getApi()) : homeSourceBean.getApi())
                    .tag(listRequestTag)
                    .params("ac", type == 0 ? "videolist" : "detail")
                    .params("t", sortData.id)
                    .params("pg", page)
                    .params(sortData.filterSelect)
                    .params("f", (sortData.filterSelect == null || sortData.filterSelect.size() <= 0) ? "" : new JSONObject(sortData.filterSelect).toString())
                    .execute(new AbsCallback<String>() {

                        @Override
                        public String convertResponse(okhttp3.Response response) throws Throwable {
                            if (response.body() != null) {
                                return response.body().string();
                            } else {
                                throw new IllegalStateException("网络请求错误");
                            }
                        }

                        @Override
                        public void onSuccess(Response<String> response) {
                            AbsXml data;
                            if (type == 0) {
                                String xml = response.body();
                                data = xml(null, xml, homeSourceBean.getKey());
                            } else {
                                String json = response.body();
                                data = json(null, json, homeSourceBean.getKey());
                            }
                            postListResult(requestSerial, data);
                        }

                        @Override
                        public void onError(Response<String> response) {
                            super.onError(response);
                            Throwable error = response.getException();
                            postListError(requestSerial, error instanceof SocketTimeoutException);
                        }
                    });
        } else if (type == 4) {
            String ext = "";
            if (sortData.filterSelect != null && sortData.filterSelect.size() > 0) {
                try {
                    LOG.i(new JSONObject(sortData.filterSelect).toString());
                    ext = Base64.encodeToString(new JSONObject(sortData.filterSelect).toString().getBytes("UTF-8"), Base64.DEFAULT | Base64.NO_WRAP);
                    LOG.i(ext);
                } catch (UnsupportedEncodingException e) {
                    e.printStackTrace();
                }
            }
            OkGo.<String>get(homeSourceBean.getApi())
                    .tag(listRequestTag)
                    .params("ac", "detail")
                    .params("filter", "true")
                    .params("t", sortData.id)
                    .params("pg", page)
                    .params("ext", ext)
                    .execute(new AbsCallback<String>() {
                        @Override
                        public String convertResponse(okhttp3.Response response) throws Throwable {
                            if (response.body() != null) {
                                return response.body().string();
                            } else {
                                throw new IllegalStateException("网络请求错误");
                            }
                        }

                        @Override
                        public void onSuccess(Response<String> response) {
                            String json = response.body();
                            LOG.i(json);
                            postListResult(requestSerial, json(null, json, homeSourceBean.getKey()));
                        }

                        @Override
                        public void onError(Response<String> response) {
                            super.onError(response);
                            Throwable error = response.getException();
                            postListError(requestSerial, error instanceof SocketTimeoutException);
                        }
                    });
        } else {
            postListError(requestSerial, false);
        }
    }

    private static final int MAX_CONCURRENT_RECOMMENDATIONS = 3;
    private static final int MAX_RECOMMENDATION_SOURCE_CATEGORIES = 8;

    private void getMovieRecommendations(SourceBean source, List<String> categoryIds, int page, long requestSerial) {
        if (page != 1) {
            postListResult(requestSerial, MovieRecommendationMerger.merge(Collections.emptyMap()));
            return;
        }
        if (categoryIds == null || categoryIds.isEmpty()) {
            postListError(requestSerial, false);
            return;
        }
        final List<String> validIds = new ArrayList<>();
        final Set<String> seen = new HashSet<>();
        for (String id : categoryIds) {
            if (id != null && !id.trim().isEmpty() && seen.add(id)) {
                validIds.add(id);
                if (validIds.size() >= MAX_RECOMMENDATION_SOURCE_CATEGORIES) {
                    break;
                }
            }
        }
        if (validIds.isEmpty()) {
            postListError(requestSerial, false);
            return;
        }

        final Map<Integer, List<Movie.Video>> results = new ConcurrentHashMap<>();
        final int totalCategories = validIds.size();
        final AtomicInteger remaining = new AtomicInteger(totalCategories);
        final AtomicInteger successes = new AtomicInteger();
        final AtomicInteger httpSuccesses = new AtomicInteger();
        final AtomicBoolean delivered = new AtomicBoolean();
        final Queue<Integer> queue = new ConcurrentLinkedQueue<>();
        for (int i = 0; i < totalCategories; i++) {
            queue.add(i);
        }

        final Runnable finish = () -> {
            if (!delivered.compareAndSet(false, true)) return;
            if (activeCategoryTimeout != null) {
                activeCategoryTimeout.cancel(false);
            }
            if (successes.get() == 0 && httpSuccesses.get() == 0) {
                postListError(requestSerial, remaining.get() > 0);
            } else {
                postListResult(requestSerial, MovieRecommendationMerger.merge(results));
            }
        };

        activeCategoryTimeout = categoryTimer.schedule(() -> {
            if (listRequestSerial.get() != requestSerial || delivered.get()) return;
            finish.run();
            OkGo.getInstance().cancelTag(listRequestTag);
        }, 20, TimeUnit.SECONDS);

        final String requestUrl = CategoryRequestUrl.normalize(source.getApi());

        final Runnable[] dispatchRef = new Runnable[1];
        dispatchRef[0] = () -> {
            if (listRequestSerial.get() != requestSerial || delivered.get()) return;
            final Integer position = queue.poll();
            if (position == null) return;
            final String categoryId = validIds.get(position);

            try {
                OkGo.<AbsXml>get(requestUrl)
                        .tag(listRequestTag)
                        .params("ac", "detail")
                        .params("t", categoryId)
                        .params("pg", 1)
                        .execute(new AbsCallback<AbsXml>() {
                            @Override
                            public AbsXml convertResponse(okhttp3.Response response) throws Throwable {
                                if (response.body() == null) throw new IllegalStateException("网络请求错误");
                                String body = response.body().string();
                                return json(null, body, source.getKey());
                            }

                            @Override
                            public void onSuccess(Response<AbsXml> response) {
                                if (listRequestSerial.get() != requestSerial) return;
                                httpSuccesses.incrementAndGet();
                                try {
                                    AbsXml data = response.body();
                                    if (data != null && data.movie != null && data.movie.videoList != null
                                            && !data.movie.videoList.isEmpty()) {
                                        results.put(position, data.movie.videoList);
                                        successes.incrementAndGet();
                                    }
                                } catch (Exception e) {
                                    e.printStackTrace();
                                } finally {
                                    if (remaining.decrementAndGet() == 0) {
                                        finish.run();
                                    } else {
                                        dispatchRef[0].run();
                                    }
                                }
                            }

                            @Override
                            public void onError(Response<AbsXml> response) {
                                super.onError(response);
                                if (listRequestSerial.get() != requestSerial) return;
                                if (remaining.decrementAndGet() == 0) {
                                    finish.run();
                                } else {
                                    dispatchRef[0].run();
                                }
                            }
                        });
            } catch (Throwable t) {
                t.printStackTrace();
                if (remaining.decrementAndGet() == 0) {
                    finish.run();
                } else {
                    dispatchRef[0].run();
                }
            }
        };

        int initialWorkers = Math.min(MAX_CONCURRENT_RECOMMENDATIONS, totalCategories);
        for (int w = 0; w < initialWorkers; w++) {
            dispatchRef[0].run();
        }
    }

    private void postListResult(long requestSerial, AbsXml data) {
        mainHandler.post(() -> {
            if (listRequestSerial.get() != requestSerial) return;
            if (data == null) {
                listError.setValue(1);
            } else {
                listError.setValue(null);
                listResult.setValue(data);
            }
        });
    }

    private void postListError(long requestSerial, boolean timeout) {
        mainHandler.post(() -> {
            if (listRequestSerial.get() == requestSerial) {
                listError.setValue(timeout ? 2 : 1);
            }
        });
    }

    interface HomeRecCallback {
        void done(List<Movie.Video> videos);
    }

    //    homeVideoContent
    void getHomeRecList(SourceBean sourceBean, ArrayList<String> ids, HomeRecCallback callback) {
        int type = sourceBean.getType();
        if (type == 3) {
            Runnable waitResponse = new Runnable() {
                @Override
                public void run() {
                    ExecutorService executor = Executors.newSingleThreadExecutor();
                    Future<String> future = executor.submit(new Callable<String>() {
                        @Override
                        public String call() throws Exception {
                            Spider sp = ApiConfig.get().getCSP(sourceBean);
                            return sp.homeVideoContent();
                        }
                    });
                    String sortJson = null;
                    try {
                        sortJson = future.get(15, TimeUnit.SECONDS);
                    } catch (TimeoutException e) {
                        e.printStackTrace();
                        future.cancel(true);
                    } catch (InterruptedException | ExecutionException e) {
                        e.printStackTrace();
                    } finally {
                        if (sortJson != null) {
                            AbsXml absXml = json(null, sortJson, sourceBean.getKey());
                            if (absXml != null && absXml.movie != null && absXml.movie.videoList != null) {
                                callback.done(absXml.movie.videoList);
                            } else {
                                callback.done(null);
                            }
                        } else {
                            callback.done(null);
                        }
                        try {
                            executor.shutdown();
                        } catch (Throwable th) {
                            th.printStackTrace();
                        }
                    }
                }
            };
            spThreadPool.execute(waitResponse);
        } else if (type == 0 || type == 1) {
            OkGo.<String>get(sourceBean.getApi())
                    .tag("detail")
                    .params("ac", sourceBean.getType() == 0 ? "videolist" : "detail")
                    .params("ids", TextUtils.join(",", ids))
                    .execute(new AbsCallback<String>() {

                        @Override
                        public String convertResponse(okhttp3.Response response) throws Throwable {
                            if (response.body() != null) {
                                return response.body().string();
                            } else {
                                throw new IllegalStateException("网络请求错误");
                            }
                        }

                        @Override
                        public void onSuccess(Response<String> response) {
                            AbsXml absXml;
                            if (sourceBean.getType() == 0) {
                                String xml = response.body();
                                absXml = xml(null, xml, sourceBean.getKey());
                            } else {
                                String json = response.body();
                                absXml = json(null, json, sourceBean.getKey());
                            }
                            if (absXml != null && absXml.movie != null && absXml.movie.videoList != null) {
                                callback.done(absXml.movie.videoList);
                            } else {
                                callback.done(null);
                            }
                        }

                        @Override
                        public void onError(Response<String> response) {
                            super.onError(response);
                            callback.done(null);
                        }
                    });
        } else {
            callback.done(null);
        }
    }

    // detailContent
    public void getDetail(String sourceKey, String urlid) {

        if (urlid.startsWith("push://") && ApiConfig.get().getSource("push_agent") != null) {
            String pushUrl = urlid.substring(7);
            if (pushUrl.startsWith("b64:")) {
                try {
                    pushUrl = new String(Base64.decode(pushUrl.substring(4), Base64.DEFAULT | Base64.URL_SAFE | Base64.NO_WRAP), "UTF-8");
                } catch (UnsupportedEncodingException e) {
                    e.printStackTrace();
                }
            } else {
                pushUrl = URLDecoder.decode(pushUrl);
            }
            sourceKey = "push_agent";
            urlid = pushUrl;
        }
        String id = urlid;
    
        SourceBean sourceBean = ApiConfig.get().getSource(sourceKey);
        if (sourceBean == null) {
            detailResult.postValue(null);
            Log.e("sourceBean", "get sourceBean got null, this should not be happended, maybe apiconfig get from http failed and use cache, sourceKey is " + sourceKey);
            return;
        }
        int type = sourceBean.getType();
        if (type == 3) {
            spThreadPool.execute(new Runnable() {
                @Override
                public void run() {
                    ExecutorService executor = Executors.newSingleThreadExecutor();
                    Future<String> future = executor.submit(new Callable<String>() {
                        @Override
                        public String call() {
                            Spider sp = ApiConfig.get().getCSP(sourceBean);
                            List<String> ids = new ArrayList<>();
                            ids.add(id);
                            try {
                                return sp.detailContent(ids);
                            } catch (Exception e) {
                                LOG.i("echo--getDetail--error: " + e.getMessage());
                                return "";
                            }
                        }
                    });

                    String json = null;
                    try {
                        json = future.get(15, TimeUnit.SECONDS);
                        LOG.i("echo--getDetail--result:" + json);
                    } catch (TimeoutException e) {
                        LOG.i("echo--getDetail--timeout");
                        future.cancel(true);
                    } catch (Exception e) {
                        LOG.i("echo--getDetail--error: " + e.getMessage());
                    } finally {
                        json(detailResult, json, sourceBean.getKey());
                        executor.shutdown();
                    }
                }
            });
        } else if (type == 0 || type == 1|| type == 4) {
            String extend=sourceBean.getExt();
            extend=getFixUrl(extend);
            com.lzy.okgo.request.GetRequest<String> request = OkGo.<String>get(sourceBean.getApi())
                    .tag("detail")
                    .params("ac", type == 0 ? "videolist" : "detail")
                    .params("ids", id);
            if (type == 4) {
                request.params("extend", extend);
            }
            request.execute(new AbsCallback<String>() {

                        @Override
                        public String convertResponse(okhttp3.Response response) throws Throwable {
                            if (response.body() != null) {
                                return response.body().string();
                            } else {
                                throw new IllegalStateException("网络请求错误");
                            }
                        }

                        @Override
                        public void onSuccess(Response<String> response) {
                            if (type == 0) {
                                String xml = response.body();
                                xml(detailResult, xml, sourceBean.getKey());
                            } else {
                                String json = response.body();
                                LOG.i(json);
                                json(detailResult, json, sourceBean.getKey());
                            }
                        }

                        @Override
                        public void onError(Response<String> response) {
                            super.onError(response);
                            json(detailResult, "", sourceBean.getKey());
                        }
                    });
        } else {
            detailResult.postValue(null);
        }
    }

    // searchContent
    public void getSearch(String sourceKey, String wd) {
        SourceBean sourceBean = ApiConfig.get().getSource(sourceKey);
        if (sourceBean == null) {
            EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_SEARCH_RESULT, null));
            return;
        }
        int type = sourceBean.getType();
        if (type == 3) {
            try {
                Spider sp = ApiConfig.get().getCSP(sourceBean);
                String search = sp.searchContent(wd, false);
                if (!TextUtils.isEmpty(search)) {
                    json(searchResult, search, sourceBean.getKey());
                } else {
                    json(searchResult, "", sourceBean.getKey());
                }
            } catch (Throwable th) {
                th.printStackTrace();
                json(searchResult, "", sourceBean.getKey());
            }
        } else if (type == 0 || type == 1) {
            OkGo.<String>get(sourceBean.getApi())
                    .params("wd", wd)
                    .params(type == 1 ? "ac" : null, type == 1 ? "detail" : null)
                    .tag("search")
                    .execute(new AbsCallback<String>() {
                        @Override
                        public String convertResponse(okhttp3.Response response) throws Throwable {
                            if (response.body() != null) {
                                return response.body().string();
                            } else {
                                throw new IllegalStateException("网络请求错误");
                            }
                        }

                        @Override
                        public void onSuccess(Response<String> response) {
                            if (type == 0) {
                                String xml = response.body();
                                xml(searchResult, xml, sourceBean.getKey());
                            } else {
                                String json = response.body();
                                json(searchResult, json, sourceBean.getKey());
                            }
                        }

                        @Override
                        public void onError(Response<String> response) {
                            super.onError(response);
                            // searchResult.postValue(null);
                            EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_SEARCH_RESULT, null));
                        }
                    });
        } else if (type == 4) {
            OkGo.<String>get(sourceBean.getApi())
                    .params("wd", wd)
                    .params("ac", "detail")
                    .params("quick", "false")
                    .tag("search")
                    .execute(new AbsCallback<String>() {
                        @Override
                        public String convertResponse(okhttp3.Response response) throws Throwable {
                            if (response.body() != null) {
                                return response.body().string();
                            } else {
                                throw new IllegalStateException("网络请求错误");
                            }
                        }

                        @Override
                        public void onSuccess(Response<String> response) {
                            String json = response.body();
                            LOG.i(json);
                            json(searchResult, json, sourceBean.getKey());
                        }

                        @Override
                        public void onError(Response<String> response) {
                            super.onError(response);
                            // searchResult.postValue(null);
                            EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_SEARCH_RESULT, null));
                        }
                    });
        } else {
            searchResult.postValue(null);
        }
    }

    // searchContent
    public void getQuickSearch(String sourceKey, String wd) {
        SourceBean sourceBean = ApiConfig.get().getSource(sourceKey);
        if (sourceBean == null) {
            EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_QUICK_SEARCH_RESULT, null));
            return;
        }
        int type = sourceBean.getType();
        if (type == 3) {
            try {
                Spider sp = ApiConfig.get().getCSP(sourceBean);
                json(quickSearchResult, sp.searchContent(wd, true), sourceBean.getKey());
            } catch (Throwable th) {
                th.printStackTrace();
            }
        } else if (type == 0 || type == 1) {
            OkGo.<String>get(sourceBean.getApi())
                    .params("wd", wd)
                    .params(type == 1 ? "ac" : null, type == 1 ? "detail" : null)
                    .tag("quick_search")
                    .execute(new AbsCallback<String>() {
                        @Override
                        public String convertResponse(okhttp3.Response response) throws Throwable {
                            if (response.body() != null) {
                                return response.body().string();
                            } else {
                                throw new IllegalStateException("网络请求错误");
                            }
                        }

                        @Override
                        public void onSuccess(Response<String> response) {
                            if (type == 0) {
                                String xml = response.body();
                                xml(quickSearchResult, xml, sourceBean.getKey());
                            } else {
                                String json = response.body();
                                json(quickSearchResult, json, sourceBean.getKey());
                            }
                        }

                        @Override
                        public void onError(Response<String> response) {
                            super.onError(response);
                            // quickSearchResult.postValue(null);
                            EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_QUICK_SEARCH_RESULT, null));
                        }
                    });
        } else if (type == 4) {
            OkGo.<String>get(sourceBean.getApi())
                    .params("wd", wd)
                    .params("ac", "detail")
                    .params("quick", "true")
                    .tag("search")
                    .execute(new AbsCallback<String>() {
                        @Override
                        public String convertResponse(okhttp3.Response response) throws Throwable {
                            if (response.body() != null) {
                                return response.body().string();
                            } else {
                                throw new IllegalStateException("网络请求错误");
                            }
                        }

                        @Override
                        public void onSuccess(Response<String> response) {
                            String json = response.body();
                            LOG.i(json);
                            json(quickSearchResult, json, sourceBean.getKey());
                        }

                        @Override
                        public void onError(Response<String> response) {
                            super.onError(response);
                            // searchResult.postValue(null);
                            EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_SEARCH_RESULT, null));
                        }
                    });
        } else {
            quickSearchResult.postValue(null);
        }
    }

    // playerContent
    //开销会不会太大了 参考 FongMi 写法优化 获取播放地址代码
    public ExecutorService threadPoolGetPlay = null;

    public void getPlay(String sourceKey, String playFlag, String progressKey, String url, String subtitleKey) {
        if (threadPoolGetPlay != null) threadPoolGetPlay.shutdownNow();
        threadPoolGetPlay = Executors.newFixedThreadPool(2);
        Callable<JSONObject> callable = () -> {
            if (Thread.currentThread().isInterrupted()) return null;
            SourceBean sourceBean = ApiConfig.get().getSource(sourceKey);
            if (sourceBean == null) {
                return null;
            }
            int type = sourceBean.getType();
            JSONObject result = null;
            if (type == 3) {
                Spider sp = ApiConfig.get().getCSP(sourceBean);
                String json = sp.playerContent(playFlag, url, ApiConfig.get().getVipParseFlags());
                result = new JSONObject(json);
            } else if (type == 0 || type == 1) {
                result = new JSONObject();
                String playUrl = sourceBean.getPlayerUrl().trim();
                boolean parse = DefaultConfig.isVideoFormat(url) && playUrl.isEmpty();
                result.put("parse", BooleanUtils.toInteger(!parse));
                result.put("url", url);
                result.put("playUrl", playUrl);
                //直接就有
            } else if (type == 4) {
                String extend=sourceBean.getExt();
                extend=getFixUrl(extend);
                okhttp3.Response response = OkGo.<String>get(sourceBean.getApi()).params("play", url).params("flag", playFlag).params("extend", extend).tag("play").execute();
                String json = response.body().string();
                result = new JSONObject(json);
            }
            if (result != null) {
                result.put("key", url);
                result.put("proKey", progressKey);
                result.put("subtKey", subtitleKey);
                if (!result.has("flag"))
                    result.put("flag", playFlag);
            }
            return result;
        };
        threadPoolGetPlay.execute(() -> {
            Future<JSONObject> future = threadPoolGetPlay.submit(callable);
            try {
                JSONObject jsonObject = future.get(15, TimeUnit.SECONDS);
                playResult.postValue(jsonObject);
            } catch (Throwable e) {
                e.printStackTrace();
                playResult.postValue(null);
            }
        });
    }
    private static final ConcurrentHashMap<String, String> extendCache = new ConcurrentHashMap<>();
    private String getFixUrl(final String extend) {
        if(!extend.startsWith("http"))return extend;
        final String key = MD5.string2MD5(extend);
        if (extendCache.containsKey(key)) {
            LOG.i("echo-getFixUrl Cache");
            return extendCache.get(key);
        }
        LOG.i("echo-getFixUrl load");
        Future<String> future = spThreadPool.submit(new Callable<String>() {
            @Override
            public String call() {
                String result = extend;
                if (extend.startsWith("http://127.0.0.1")) {
                    String path = extend.replaceAll("^http.+/file/", FileUtils.getRootPath() + "/");
                    path = path.replaceAll("localhost/", "/");
                    result = FileUtils.readFileToString(path, "UTF-8");
                    result = tryMinifyJson(result);
                    extendCache.putIfAbsent(key, result);
                } else if (extend.startsWith("http")) {
                    result = OkHttpUtil.string(extend, null);
                    if (!result.isEmpty()) {
                        result = tryMinifyJson(result);
                        extendCache.putIfAbsent(key, result);
                    }
                }
                return result;
            }
        });

        try {
            return future.get(5, TimeUnit.SECONDS);
        } catch (TimeoutException te) {
            te.printStackTrace();
            future.cancel(true);
            return extend;
        } catch (Exception e) {
            e.printStackTrace();
            return extend;
        }
    }

    private String tryMinifyJson(String raw) {
        try {
            raw = raw.trim();
            JsonElement jsonElement = JsonParser.parseString(raw);
            return gson.toJson(jsonElement);
        } catch (Exception e) {
            return raw;
        }

    }

    private MovieSort.SortFilter getSortFilter(JsonObject obj) {
        String key = obj.get("key").getAsString();
        String name = obj.get("name").getAsString();
        JsonArray kv = obj.getAsJsonArray("value");
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        for (JsonElement ele : kv) {
            JsonObject ele_obj = ele.getAsJsonObject();
            String values_key=ele_obj.has("n")?ele_obj.get("n").getAsString():"";
            String values_value=ele_obj.has("v")?ele_obj.get("v").getAsString():"";
            values.put(values_key, values_value);
        }
        MovieSort.SortFilter filter = new MovieSort.SortFilter();
        filter.key = key;
        filter.name = name;
        filter.values = values;
        return filter;
    }

    private AbsSortXml sortJson(MutableLiveData<AbsSortXml> result, String json) {
        try {
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            AbsSortJson sortJson = gson.fromJson(obj, new TypeToken<AbsSortJson>() {
            }.getType());
            AbsSortXml data = sortJson.toAbsSortXml();
            try {
                if (obj.has("filters")) {
                    LinkedHashMap<String, ArrayList<MovieSort.SortFilter>> sortFilters = new LinkedHashMap<>();
                    JsonObject filters = obj.getAsJsonObject("filters");
                    for (String key : filters.keySet()) {
                        ArrayList<MovieSort.SortFilter> sortFilter = new ArrayList<>();
                        JsonElement one = filters.get(key);
                        if (one.isJsonObject()) {
                            sortFilter.add(getSortFilter(one.getAsJsonObject()));
                        } else {
                            for (JsonElement ele : one.getAsJsonArray()) {
                                sortFilter.add(getSortFilter(ele.getAsJsonObject()));
                            }
                        }
                        sortFilters.put(key, sortFilter);
                    }
                    for (MovieSort.SortData sort : data.classes.sortList) {
                        if (sortFilters.containsKey(sort.id) && sortFilters.get(sort.id) != null) {
                            sort.filters = sortFilters.get(sort.id);
                        }
                    }
                }
            } catch (Throwable th) {

            }
            return data;
        } catch (Exception e) {
            return null;
        }
    }

    private AbsSortXml sortXml(MutableLiveData<AbsSortXml> result, String xml) {
        try {
            XStream xstream = new XStream(new DomDriver());//创建Xstram对象
            xstream.autodetectAnnotations(true);
            xstream.processAnnotations(AbsSortXml.class);
            xstream.ignoreUnknownElements();
            xstream.allowTypes(new Class[]{AbsSortXml.class});
            AbsSortXml data = (AbsSortXml) xstream.fromXML(xml);
            for (MovieSort.SortData sort : data.classes.sortList) {
                if (sort.filters == null) {
                    sort.filters = new ArrayList<>();
                }
            }
            return data;
        } catch (Exception e) {
            return null;
        }
    }

    private void absXml(AbsXml data, String sourceKey) {
        if (data.movie != null && data.movie.videoList != null) {
            for (Movie.Video video : data.movie.videoList) {
                if (video.urlBean != null && video.urlBean.infoList != null) {
                    for (Movie.Video.UrlBean.UrlInfo urlInfo : video.urlBean.infoList) {
                        String[] str = null;
                        if (urlInfo.urls.contains("#")) {
                            str = urlInfo.urls.split("#");
                        } else {
                            str = new String[]{urlInfo.urls};
                        }
                        List<Movie.Video.UrlBean.UrlInfo.InfoBean> infoBeanList = new ArrayList<>();
//                        for (String s : str) {
//                            if (s.contains("$")) {
//                                String[] ss = s.split("\\$");
//                                if (ss.length >= 2) {
//                                    infoBeanList.add(new Movie.Video.UrlBean.UrlInfo.InfoBean(ss[0], ss[1]));
//                                }
//                                //infoBeanList.add(new Movie.Video.UrlBean.UrlInfo.InfoBean(s.substring(0, s.indexOf("$")), s.substring(s.indexOf("$") + 1)));
//                            }
//                        }
                        for (String s : str) {
                            String[] ss = s.split("\\$");
                            if (ss.length > 0) {
                                if (ss.length >= 2) {
                                    infoBeanList.add(new Movie.Video.UrlBean.UrlInfo.InfoBean(ss[0], ss[1]));
                                } else {
                                    infoBeanList.add(new Movie.Video.UrlBean.UrlInfo.InfoBean((infoBeanList.size() + 1) + "", ss[0]));
                                }
                            }
                        }
                        urlInfo.beanList = infoBeanList;
                    }
                }
                if (video.sourceKey == null)
                    video.sourceKey = sourceKey;
            }
        }
    }

    private AbsXml checkPush(AbsXml data) {
        if (data.movie != null && data.movie.videoList != null && data.movie.videoList.size() > 0) {
            Movie.Video video = data.movie.videoList.get(0);
            if (video != null && video.urlBean != null && video.urlBean.infoList != null && video.urlBean.infoList.size() > 0) {
                for (int i = 0; i < video.urlBean.infoList.size(); i++) {
                    Movie.Video.UrlBean.UrlInfo urlinfo = video.urlBean.infoList.get(i);
                    if (urlinfo != null && urlinfo.beanList != null && !urlinfo.beanList.isEmpty()) {
                        for (Movie.Video.UrlBean.UrlInfo.InfoBean infoBean : urlinfo.beanList) {
                            if (infoBean.url.startsWith("push://")) {
                                String pushUrl = infoBean.url.substring(7);
                                if (pushUrl.startsWith("b64:")) {
                                    try {
                                        pushUrl = new String(Base64.decode(pushUrl.substring(4), Base64.DEFAULT | Base64.URL_SAFE | Base64.NO_WRAP), "UTF-8");
                                    } catch (UnsupportedEncodingException e) {
                                        e.printStackTrace();
                                    }
                                } else {
                                    pushUrl = URLDecoder.decode(pushUrl);
                                }

                                final AbsXml[] resData = {null};

                                final CountDownLatch countDownLatch = new CountDownLatch(1);
                                ExecutorService threadPool = Executors.newSingleThreadExecutor();
                                String finalPushUrl = pushUrl;
                                threadPool.execute(new Runnable() {
                                    @Override
                                    public void run() {
                                        SourceBean sb = ApiConfig.get().getSource("push_agent");
                                        if (sb == null) {
                                            countDownLatch.countDown();
                                            return;
                                        }
                                        if (sb.getType() == 4) {
                                            OkGo.<String>get(sb.getApi())
                                                    .tag("detail")
                                                    .params("ac", "detail")
                                                    .params("ids", finalPushUrl)
                                                    .execute(new AbsCallback<String>() {
                                                        @Override
                                                        public String convertResponse(okhttp3.Response response) throws Throwable {
                                                            if (response.body() != null) {
                                                                return response.body().string();
                                                            } else {
                                                                return "";
                                                            }
                                                        }

                                                        @Override
                                                        public void onSuccess(Response<String> response) {
                                                            String res = response.body();
                                                            if (!TextUtils.isEmpty(res)) {
                                                                try {
                                                                    AbsJson absJson = gson.fromJson(res, new TypeToken<AbsJson>() {
                                                                    }.getType());
                                                                    resData[0] = absJson.toAbsXml();
                                                                    absXml(resData[0], sb.getKey());
                                                                } catch (Exception e) {
                                                                    e.printStackTrace();
                                                                }
                                                            }
                                                            countDownLatch.countDown();
                                                        }

                                                        @Override
                                                        public void onError(Response<String> response) {
                                                            super.onError(response);
                                                            countDownLatch.countDown();
                                                        }
                                                    });
                                        } else {
                                            try {
                                                Spider sp = ApiConfig.get().getCSP(sb);
                                                //   ApiConfig.get().setPlayJarKey(sb.getJar());
                                                List<String> ids = new ArrayList<>();
                                                ids.add(finalPushUrl);
                                                String res = sp.detailContent(ids);
                                                if (!TextUtils.isEmpty(res)) {
                                                    try {
                                                        AbsJson absJson = gson.fromJson(res, new TypeToken<AbsJson>() {
                                                        }.getType());
                                                        resData[0] = absJson.toAbsXml();
                                                        absXml(resData[0], sb.getKey());
                                                    } catch (Exception e) {
                                                        e.printStackTrace();
                                                    }
                                                }
                                            } catch (Throwable th) {
                                                th.printStackTrace();
                                            }
                                            countDownLatch.countDown();
                                        }
                                    }
                                });
                                try {
                                    countDownLatch.await(15, TimeUnit.SECONDS);
                                    threadPool.shutdown();
                                } catch (InterruptedException e) {
                                    e.printStackTrace();
                                }
                                if (resData[0] != null) {
                                    AbsXml res = resData[0];
                                    if (res.movie != null && res.movie.videoList != null && res.movie.videoList.size() > 0) {
                                        Movie.Video resVideo = res.movie.videoList.get(0);
                                        if (resVideo != null && resVideo.urlBean != null && resVideo.urlBean.infoList != null && resVideo.urlBean.infoList.size() > 0) {
                                            if (urlinfo.beanList.size() == 1) {
                                                video.urlBean.infoList.remove(i);
                                            } else {
                                                urlinfo.beanList.remove(infoBean);
                                            }
                                            for (Movie.Video.UrlBean.UrlInfo resUrlinfo : resVideo.urlBean.infoList) {
                                                if (resUrlinfo != null && resUrlinfo.beanList != null && !resUrlinfo.beanList.isEmpty()) {
                                                    video.urlBean.infoList.add(resUrlinfo);
                                                }
                                            }
                                            video.sourceKey = "push_agent";
                                            return data;
                                        }
                                    }
                                }
                                infoBean.name = "解析失败 >>> " + infoBean.name;
                            }
                        }
                    }
                }
            }
        }
        return data;
    }

    public void checkThunder(AbsXml data, int index) {
        boolean thunderParse = false;
        if (data.movie != null && data.movie.videoList != null && data.movie.videoList.size() == 1) {
            Movie.Video video = data.movie.videoList.get(0);
            if (video != null && video.urlBean != null && video.urlBean.infoList != null) {
            	boolean hasThunder=false;
                thunderLoop:
                for (int idx=0;idx<video.urlBean.infoList.size();idx++) {
                    Movie.Video.UrlBean.UrlInfo urlInfo = video.urlBean.infoList.get(idx);
                    for (Movie.Video.UrlBean.UrlInfo.InfoBean infoBean : urlInfo.beanList) {
                        if(Thunder.isSupportUrl(infoBean.url)){
                            hasThunder=true;
                            break thunderLoop;
                        }
                    }
                }
                if (hasThunder) {
                    thunderParse = true;
                    Thunder.parse(App.getInstance(), video.urlBean, new Thunder.ThunderCallback() {
                        @Override
                        public void status(int code, String info) {
                            if (code >= 0) {
                                LOG.i(info);
                            } else {
                                video.urlBean.infoList.get(0).beanList.get(0).name = info;
                                detailResult.postValue(data);
                            }
                        }

                        @Override
                        public void list(Map<Integer, String> urlMap) {
                            for (int key : urlMap.keySet()) {
                                String playList=urlMap.get(key);
                                video.urlBean.infoList.get(key).urls = playList;
                                String[] str = playList.split("#");
                                List<Movie.Video.UrlBean.UrlInfo.InfoBean> infoBeanList = new ArrayList<>();
                                for (String s : str) {
                                    if (s.contains("$")) {
                                        String[] ss = s.split("\\$");

                                        if (ss.length > 0) {
                                            if (ss.length >= 2) {
                                                infoBeanList.add(new Movie.Video.UrlBean.UrlInfo.InfoBean(ss[0], ss[1]));
                                            } else {
                                                infoBeanList.add(new Movie.Video.UrlBean.UrlInfo.InfoBean((infoBeanList.size() + 1) + "", ss[0]));
                                            }
                                        }
                                    }
                                }
                                video.urlBean.infoList.get(key).beanList = infoBeanList;
                            }
                            detailResult.postValue(data);
                        }

                        @Override
                        public void play(String url) {

                        }
                    });
                }
            }
        }
        if (!thunderParse && index==0) {
            detailResult.postValue(data);
        }
    }

    private AbsXml xml(MutableLiveData<AbsXml> result, String xml, String sourceKey) {
        try {
            XStream xstream = new XStream(new DomDriver());//创建Xstram对象
            xstream.autodetectAnnotations(true);
            xstream.processAnnotations(AbsXml.class);
            xstream.ignoreUnknownElements();
            xstream.allowTypes(new Class[]{AbsXml.class});
            if (xml.contains("<year></year>")) {
                xml = xml.replace("<year></year>", "<year>0</year>");
            }
            if (xml.contains("<state></state>")) {
                xml = xml.replace("<state></state>", "<state>0</state>");
            }
            AbsXml data = (AbsXml) xstream.fromXML(xml);
            absXml(data, sourceKey);
            if (searchResult == result) {
                EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_SEARCH_RESULT, data));
            } else if (quickSearchResult == result) {
                EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_QUICK_SEARCH_RESULT, data));
            } else if (result != null) {
                if (result == detailResult) {
                    data = checkPush(data);
                    checkThunder(data, 0);
                } else {
                    result.postValue(data);
                }
            }
            return data;
        } catch (Exception e) {
            if (searchResult == result) {
                EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_SEARCH_RESULT, null));
            } else if (quickSearchResult == result) {
                EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_QUICK_SEARCH_RESULT, null));
            } else if (result != null) {
                result.postValue(null);
            }
            return null;
        }
    }

    private AbsXml json(MutableLiveData<AbsXml> result, String json, String sourceKey) {
        try {
            // 测试数据
            /*json = "{\n" +
                    "\t\"list\": [{\n" +
                    "\t\t\"vod_id\": \"137133\",\n" +
                    "\t\t\"vod_name\": \"磁力测试\",\n" +
                    "\t\t\"vod_pic\": \"https:/img9.doubanio.com/view/photo/s_ratio_poster/public/p2656327176.webp\",\n" +
                    "\t\t\"type_name\": \"剧情 / 爱情 / 古装\",\n" +
                    "\t\t\"vod_year\": \"2022\",\n" +
                    "\t\t\"vod_area\": \"中国大陆\",\n" +
                    "\t\t\"vod_remarks\": \"40集全\",\n" +
                    "\t\t\"vod_actor\": \"刘亦菲\",\n" +
                    "\t\t\"vod_director\": \"杨阳\",\n" +
                    "\t\t\"vod_content\": \"　　在钱塘开茶铺的赵盼儿（刘亦菲 饰）惊闻未婚夫、新科探花欧阳旭（徐海乔 饰）要另娶当朝高官之女，不甘命运的她誓要上京讨个公道。在途中她遇到了出自权门但生性正直的皇城司指挥顾千帆（陈晓 饰），并卷入江南一场大案，两人不打不相识从而结缘。赵盼儿凭借智慧解救了被骗婚而惨遭虐待的“江南第一琵琶高手”宋引章（林允 饰）与被苛刻家人逼得离家出走的豪爽厨娘孙三娘（柳岩 饰），三位姐妹从此结伴同行，终抵汴京，见识世间繁华。为了不被另攀高枝的欧阳旭从东京赶走，赵盼儿与宋引章、孙三娘一起历经艰辛，将小小茶坊一步步发展为汴京最大的酒楼，揭露了负心人的真面目，收获了各自的真挚感情和人生感悟，也为无数平凡女子推开了一扇平等救赎之门。\",\n" +
                    "\t\t\"vod_play_from\": \"磁力测试\",\n" +
                    "\t\t\"vod_play_url\": \"0$magnet:?xt=urn:btih:9e9358b946c427962533472efdd2efd9e9e38c67&dn=%e9%98%b3%e5%85%89%e7%94%b5%e5%bd%b1www.ygdy8.com.%e7%83%ad%e8%a1%80.2022.BD.1080P.%e9%9f%a9%e8%af%ad%e4%b8%ad%e8%8b%b1%e5%8f%8c%e5%ad%97.mkv&tr=udp%3a%2f%2ftracker.opentrackr.org%3a1337%2fannounce&tr=udp%3a%2f%2fexodus.desync.com%3a6969%2fannounce\"\n" +
                    "\t}]\n" +
                    "}";*/
            AbsJson absJson = gson.fromJson(json, new TypeToken<AbsJson>() {
            }.getType());
            AbsXml data = absJson.toAbsXml();
            absXml(data, sourceKey);
            if (searchResult == result) {
                EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_SEARCH_RESULT, data));
            } else if (quickSearchResult == result) {
                EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_QUICK_SEARCH_RESULT, data));
            } else if (result != null) {
                if (result == detailResult) {
                    data = checkPush(data);
                    checkThunder(data, 0);
                } else {
                    result.postValue(data);
                }
            }
            return data;
        } catch (Exception e) {
            if (searchResult == result) {
                EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_SEARCH_RESULT, null));
            } else if (quickSearchResult == result) {
                EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_QUICK_SEARCH_RESULT, null));
            } else if (result != null) {
                result.postValue(null);
            }
            return null;
        }
    }

    @Override
    protected void onCleared() {
        super.onCleared();
        listRequestSerial.incrementAndGet();
        OkGo.getInstance().cancelTag(listRequestTag);
        if (activeCategoryFuture != null) activeCategoryFuture.cancel(true);
        if (activeCategoryTimeout != null) activeCategoryTimeout.cancel(false);
        if (activeCategoryExecutor != null) activeCategoryExecutor.shutdownNow();
        categoryTimer.shutdownNow();
        closeExecutor(threadPoolGetPlay);

    }

    private void closeExecutor(ExecutorService executorService) {
        if (executorService != null) {
            try {
                executorService.shutdownNow();
            } catch (Throwable ignored) {
            }
        }
    }
}
