package com.github.tvbox.osc.ui.activity;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.RecyclerView;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.github.catvod.crawler.JsLoader;
import com.github.tvbox.osc.ui.tv.Motion;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.base.BaseActivity;
import com.github.tvbox.osc.bean.AbsXml;
import com.github.tvbox.osc.bean.Movie;
import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.event.RefreshEvent;
import com.github.tvbox.osc.event.ServerEvent;
import com.github.tvbox.osc.ui.adapter.FastListAdapter;
import com.github.tvbox.osc.ui.adapter.FastSearchAdapter;
import com.github.tvbox.osc.ui.adapter.SearchWordAdapter;
import com.github.tvbox.osc.util.SearchKeywords;
import com.github.tvbox.osc.util.FastClickCheckUtil;
import com.github.tvbox.osc.util.PlayableStreamFilter;
import com.github.tvbox.osc.util.SearchHelper;
import com.github.tvbox.osc.util.SearchResultMerger;
import com.github.tvbox.osc.util.SiteQualityStore;
import com.github.tvbox.osc.util.SmartSourceResolver;
import com.github.tvbox.osc.util.SourceRanker;
import com.github.tvbox.osc.viewmodel.SourceViewModel;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.lzy.okgo.OkGo;
import com.lzy.okgo.callback.AbsCallback;
import com.lzy.okgo.model.Response;
import com.owen.tvrecyclerview.widget.TvRecyclerView;
import com.owen.tvrecyclerview.widget.V7GridLayoutManager;
import com.owen.tvrecyclerview.widget.V7LinearLayoutManager;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @author pj567
 * @date :2020/12/23
 * @description:
 */
public class FastSearchActivity extends BaseActivity {
    private LinearLayout llLayout;
    private TextView mSearchTitle;
    private TextView btnPlayableFilter;
    private PlayableStreamFilter streamFilter;
    private TvRecyclerView mGridView;
    private TvRecyclerView mGridViewFilter;
    private TvRecyclerView mGridViewWord;
    private TvRecyclerView mGridViewWordFenci;
    SourceViewModel sourceViewModel;

    private SearchWordAdapter searchWordAdapter;
    private FastSearchAdapter searchAdapter;
    private FastSearchAdapter searchAdapterFilter;
    private FastListAdapter spListAdapter;
    private String searchTitle = "";
    private HashMap<String, String> spNames;
    private boolean isFilterMode = false;
    private String searchFilterKey = "";    // 过滤的key
    private HashMap<String, ArrayList<Movie.Video>> resultVods; // 搜索结果
    private int finishedCount = 0;
    private final List<String> quickSearchWord = new ArrayList<>();

    // 结果排序：先缓冲 1.2 秒再按可播性排序刷新，避免结果边到边跳；总等待最多 12 秒
    private static final long FLUSH_DELAY_MS = 1200;
    private static final long SEARCH_DEADLINE_MS = 12000;
    private final List<Movie.Video> pendingVods = new ArrayList<>();
    private final HashMap<String, Long> siteElapsed = new HashMap<>();
    private final Handler rankHandler = new Handler(Looper.getMainLooper());
    private long searchStartAt = 0;
    private boolean searchDeadlineReached = false;
    private boolean showAllFocused = false;
    private boolean flushScheduled = false;
    private final Runnable flushRunnable = new Runnable() {
        @Override
        public void run() {
            flushPending();
        }
    };
    private final Runnable deadlineRunnable = new Runnable() {
        @Override
        public void run() {
            searchDeadlineReached = true;
            flushPending();
            if (streamFilter != null) {
                streamFilter.markSearchCompleted();
            }
            if (searchAdapter.getData().isEmpty() && (streamFilter == null || !streamFilter.isProbing())) {
                showEmpty();
            }
        }
    };
    private HashMap<String, String> mCheckSources = null;

    private final View.OnFocusChangeListener focusChangeListener = new View.OnFocusChangeListener() {
        @Override
        public void onFocusChange(View itemView, boolean hasFocus) {
            try {
                if (!hasFocus) {
                    spListAdapter.onLostFocus(itemView);
                } else {
                    int ret = spListAdapter.onSetFocus(itemView);
                    if (ret < 0) return;
                    TextView v = (TextView) itemView;
                    String sb = v.getText().toString();
                    filterResult(sb);
                }
            } catch (Exception e) {
                Toast.makeText(FastSearchActivity.this, e.toString(), Toast.LENGTH_SHORT).show();
            }
        }
    };

    @Override
    protected int getLayoutResID() {
        return R.layout.activity_fast_search;
    }

    @Override
    protected void init() {
        spNames = new HashMap<String, String>();
        resultVods = new HashMap<String, ArrayList<Movie.Video>>();
        initView();
        initViewModel();
        initData();
    }

    private List<Runnable> pauseRunnable = null;

    @Override
    protected void onResume() {
        super.onResume();
        if (pauseRunnable != null && pauseRunnable.size() > 0) {
            searchExecutorService = Executors.newFixedThreadPool(5);
            allRunCount.set(pauseRunnable.size());
            for (Runnable runnable : pauseRunnable) {
                searchExecutorService.execute(runnable);
            }
            pauseRunnable.clear();
            pauseRunnable = null;
        }
    }

    private void initView() {
        EventBus.getDefault().register(this);
        llLayout = findViewById(R.id.llLayout);
        mSearchTitle = findViewById(R.id.mSearchTitle);
        mGridView = findViewById(R.id.mGridView);
        mGridViewWord = findViewById(R.id.mGridViewWord);
        mGridViewFilter = findViewById(R.id.mGridViewFilter);
        btnPlayableFilter = findViewById(R.id.btnPlayableFilter);
        streamFilter = new PlayableStreamFilter(new PlayableStreamFilter.Callback() {
            @Override
            public void onVerifiedPlayable(Movie.Video video) {
                if (!streamFilter.isPlayableOnly() || isFinishing()) return;
                List<Movie.Video> single = new ArrayList<>(1);
                single.add(video);
                addWordAdapterIfNeed(video.sourceKey);
                if (SearchResultMerger.merge(searchAdapter, mGridView, single, searchTitle, siteElapsed)) {
                    showSuccess();
                    if (!isFilterMode) mGridView.setVisibility(View.VISIBLE);
                }
            }

            @Override
            public void onProgressChanged(int playableCount, int totalCount, boolean probing) {
                updateHeaderText(playableCount, totalCount, probing);
            }

            @Override
            public void onAllFinished(int playableCount, int totalCount) {
                if (isFinishing()) return;
                if (streamFilter.isPlayableOnly() && playableCount == 0 && totalCount > 0) {
                    streamFilter.setPlayableOnly(false);
                    updateFilterButton();
                    rebuildDisplayedList();
                    Toast.makeText(FastSearchActivity.this, R.string.fs_no_playable_tip, Toast.LENGTH_SHORT).show();
                } else if (searchAdapter.getData().isEmpty()) {
                    showEmpty();
                }
            }
        });
        updateFilterButton();
        btnPlayableFilter.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) Motion.focusButton(v);
            else Motion.unfocus(v);
        });
        btnPlayableFilter.setOnClickListener(v -> {
            FastClickCheckUtil.check(v);
            streamFilter.setPlayableOnly(!streamFilter.isPlayableOnly());
            updateFilterButton();
            rebuildDisplayedList();
        });

        mGridViewWord.setHasFixedSize(true);
        mGridViewWord.setLayoutManager(new V7LinearLayoutManager(this.mContext, 1, false));
        spListAdapter = new FastListAdapter();
        mGridViewWord.setAdapter(spListAdapter);

//        mGridViewWord.setFocusable(true);
//        mGridViewWord.setOnFocusChangeListener(new View.OnFocusChangeListener() {
//            @Override
//            public void onFocusChange(View itemView, boolean hasFocus) {}
//        });

        mGridViewWord.addOnChildAttachStateChangeListener(new RecyclerView.OnChildAttachStateChangeListener() {
            @Override
            public void onChildViewAttachedToWindow(@NonNull View child) {
                child.setFocusable(true);
                child.setOnFocusChangeListener(focusChangeListener);
                TextView t = (TextView) child;
                // 只在每次搜索第一次出现“全部显示”时抢焦点，之后追加站点不再打断用户
                if (!showAllFocused && getString(R.string.fs_show_all).contentEquals(t.getText())) {
                    showAllFocused = true;
                    t.requestFocus();
                }
//                if (child.isFocusable() && null == child.getOnFocusChangeListener()) {
//                    child.setOnFocusChangeListener(focusChangeListener);
//                }
            }

            @Override
            public void onChildViewDetachedFromWindow(@NonNull View view) {
                view.setOnFocusChangeListener(null);
            }
        });

        spListAdapter.setOnItemClickListener(new BaseQuickAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(BaseQuickAdapter adapter, View view, int position) {
                String spName = spListAdapter.getItem(position);
                filterResult(spName);
            }
        });
        // 添加选中放大效果
        mGridView.setOnItemListener(new TvRecyclerView.OnItemListener() {
            @Override
            public void onItemPreSelected(TvRecyclerView parent, View itemView, int position) {
                Motion.unfocus(itemView);
            }

            @Override
            public void onItemSelected(TvRecyclerView parent, View itemView, int position) {
                Motion.focusCard(itemView);
            }

            @Override
            public void onItemClick(TvRecyclerView parent, View itemView, int position) {

            }
        });
        // mGridView.setHasFixedSize(true);
        mGridView.setLayoutManager(new V7GridLayoutManager(this.mContext, 5));

        searchAdapter = new FastSearchAdapter();
        mGridView.setAdapter(searchAdapter);

        searchAdapter.setOnItemClickListener(new BaseQuickAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(BaseQuickAdapter adapter, View view, int position) {
                FastClickCheckUtil.check(view);
                Movie.Video video = searchAdapter.getData().get(position);
                if (video != null) {
                    try {
                        if (searchExecutorService != null) {
                            pauseRunnable = searchExecutorService.shutdownNow();
                            searchExecutorService = null;
                            JsLoader.stopAll();
                        }
                    } catch (Throwable th) {
                        th.printStackTrace();
                    }
                    openDetail(video);
                }
            }
        });

        mGridViewFilter.setLayoutManager(new V7GridLayoutManager(this.mContext, 5));
        searchAdapterFilter = new FastSearchAdapter();
        mGridViewFilter.setAdapter(searchAdapterFilter);
        searchAdapterFilter.setOnItemClickListener(new BaseQuickAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(BaseQuickAdapter adapter, View view, int position) {
                FastClickCheckUtil.check(view);
                Movie.Video video = searchAdapterFilter.getData().get(position);
                if (video != null) {
                    try {
                        if (searchExecutorService != null) {
                            pauseRunnable = searchExecutorService.shutdownNow();
                            searchExecutorService = null;
                            JsLoader.stopAll();
                        }
                    } catch (Throwable th) {
                        th.printStackTrace();
                    }
                    openDetail(video);
                }
            }
        });

        setLoadSir(llLayout);

        // 分词
        searchWordAdapter = new SearchWordAdapter();
        mGridViewWordFenci = findViewById(R.id.mGridViewWordFenci);
        mGridViewWordFenci.setAdapter(searchWordAdapter);
        mGridViewWordFenci.setLayoutManager(new V7LinearLayoutManager(this.mContext, 0, false));
        searchWordAdapter.setOnItemClickListener(new BaseQuickAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(BaseQuickAdapter adapter, View view, int position) {
                String str = searchWordAdapter.getData().get(position);
                search(str);
            }
        });
        searchWordAdapter.setNewData(new ArrayList<>());
    }

    private void initViewModel() {
        sourceViewModel = new ViewModelProvider(this).get(SourceViewModel.class);
    }

    private void filterResult(String spName) {
        if (getString(R.string.fs_show_all).equals(spName)) {
            mGridView.setVisibility(View.VISIBLE);
            mGridViewFilter.setVisibility(View.GONE);
            return;
        }
        String key = spNames.get(spName);
        if (key == null || key.isEmpty()) return;

        if (key.equals(searchFilterKey)) return;
        searchFilterKey = key;

        List<Movie.Video> list = resultVods.get(key);
        // 复制一份：setNewData 会直接持有传入的列表
        searchAdapterFilter.setNewData(list == null ? new ArrayList<>() : new ArrayList<>(list));
        mGridView.setVisibility(View.GONE);
        mGridViewFilter.setVisibility(View.VISIBLE);
    }

    private void fenci() {
        if (!quickSearchWord.isEmpty()) return; // 已有分词结果就不再重复生成
        // 本地生成候选关键词。原实现调用第三方分词接口，会把片名明文外发，
        // 且请求里带着硬编码的 app_key/sign，与隐私声明冲突。
        quickSearchWord.clear();
        quickSearchWord.addAll(SearchKeywords.from(searchTitle));
        EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_QUICK_SEARCH_WORD, quickSearchWord));
    }

    private void initCheckedSourcesForSearch() {
        mCheckSources = SearchHelper.getSourcesForSearch();
    }

    private void initData() {
        initCheckedSourcesForSearch();
        Intent intent = getIntent();
        if (intent != null && intent.hasExtra("title")) {
            String title = intent.getStringExtra("title");
            showLoading();
            search(title);
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void server(ServerEvent event) {
        if (event.type == ServerEvent.SERVER_SEARCH) {
            String title = (String) event.obj;
            showLoading();
            search(title);
        }
    }

    private void updateFilterButton() {
        if (btnPlayableFilter == null || streamFilter == null) return;
        boolean playableOnly = streamFilter.isPlayableOnly();
        btnPlayableFilter.setActivated(playableOnly);
        btnPlayableFilter.setText(playableOnly ? R.string.fs_filter_playable : R.string.fs_filter_all);
    }

    private void updateHeaderText(int playableCount, int totalCount, boolean probing) {
        if (mSearchTitle == null || streamFilter == null) return;
        if (streamFilter.isPlayableOnly()) {
            mSearchTitle.setText(probing
                    ? getString(R.string.fs_results_probing, playableCount)
                    : getString(R.string.fs_results_playable, playableCount));
        } else {
            mSearchTitle.setText(getString(R.string.fs_results_all, totalCount));
        }
    }

    private void rebuildDisplayedList() {
        if (streamFilter == null) return;
        List<Movie.Video> source = streamFilter.isPlayableOnly()
                ? streamFilter.getVerifiedItems()
                : streamFilter.getAllItems();
        List<Movie.Video> ranked = SourceRanker.rank(source, SearchResultMerger.VIDEO_KEYS,
                searchTitle, SiteQualityStore.quality(), siteElapsed);
        searchAdapter.setNewData(ranked);
        if (!ranked.isEmpty()) {
            showSuccess();
            if (!isFilterMode) mGridView.setVisibility(View.VISIBLE);
        } else if (!streamFilter.isProbing()) {
            showEmpty();
        }
        updateHeaderText(streamFilter.getPlayableCount(), streamFilter.getTotalCount(), streamFilter.isProbing());
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void refresh(RefreshEvent event) {
        if (event.type == RefreshEvent.TYPE_SEARCH_RESULT) {
            try {
                searchData(event.obj == null ? null : (AbsXml) event.obj);
            } catch (Exception e) {
                searchData(null);
            }
        } else if (event.type == RefreshEvent.TYPE_QUICK_SEARCH_WORD) {
            if (event.obj != null) {
                List<String> data = (List<String>) event.obj;
                searchWordAdapter.setNewData(data);
            }
        }
    }

    private void search(String title) {
        cancel();
        showLoading();
        this.searchTitle = title;
        fenci();
        mGridView.setVisibility(View.INVISIBLE);
        mGridViewFilter.setVisibility(View.GONE);
        searchAdapter.setNewData(new ArrayList<>());
        searchAdapterFilter.setNewData(new ArrayList<>());

        spListAdapter.reset();
        resultVods.clear();
        searchFilterKey = "";
        isFilterMode = false;
        spNames.clear();
        finishedCount = 0;
        rankHandler.removeCallbacksAndMessages(null);
        flushScheduled = false;
        pendingVods.clear();
        siteElapsed.clear();
        searchDeadlineReached = false;
        showAllFocused = false;
        if (streamFilter != null) {
            streamFilter.reset();
            updateHeaderText(0, 0, true);
        }
        searchStartAt = SystemClock.elapsedRealtime();
        rankHandler.postDelayed(deadlineRunnable, SEARCH_DEADLINE_MS);

        searchResult();
    }

    private ExecutorService searchExecutorService = null;
    private final AtomicInteger allRunCount = new AtomicInteger(0);

    private void searchResult() {
        try {
            if (searchExecutorService != null) {
                searchExecutorService.shutdownNow();
                searchExecutorService = null;
                JsLoader.stopAll();
            }
        } catch (Throwable th) {
            th.printStackTrace();
        } finally {
            searchAdapter.setNewData(new ArrayList<>());
            searchAdapterFilter.setNewData(new ArrayList<>());
            allRunCount.set(0);
        }
        searchExecutorService = Executors.newFixedThreadPool(5);
        List<SourceBean> searchRequestList = new ArrayList<>();
        searchRequestList.addAll(ApiConfig.get().getSourceBeanList());
        // 优先派发高分非熔断站点，让最快可播源第一时间返回并验证通过
        java.util.Collections.sort(searchRequestList, (a, b) ->
                Double.compare(SiteQualityStore.score(b.getKey()), SiteQualityStore.score(a.getKey())));

        ArrayList<String> siteKey = new ArrayList<>();
        ArrayList<String> hots = new ArrayList<>();

        spListAdapter.setNewData(hots);
        spListAdapter.addData(getString(R.string.fs_show_all));
        for (SourceBean bean : searchRequestList) {
            if (!bean.isSearchable()) {
                continue;
            }
            if (mCheckSources != null && !mCheckSources.containsKey(bean.getKey())) {
                continue;
            }
            siteKey.add(bean.getKey());
            this.spNames.put(bean.getName(), bean.getKey());
            allRunCount.incrementAndGet();
        }

        for (String key : siteKey) {
            searchExecutorService.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        sourceViewModel.getSearch(key, searchTitle);
                    } catch (Exception e) {

                    }
                }
            });
        }
    }

    // 向过滤栏添加有结果的spname
    private String addWordAdapterIfNeed(String key) {
        try {
            String name = "";
            for (String n : spNames.keySet()) {
                if (key != null && key.equals(spNames.get(n))) {
                    name = n;
                }
            }
            if (name.isEmpty()) return key;

            List<String> names = spListAdapter.getData();
            for (int i = 0; i < names.size(); ++i) {
                if (name.equals(names.get(i))) {
                    return key;
                }
            }

            spListAdapter.addData(name);
            return key;
        } catch (Exception e) {
            return key;
        }
    }

    private void searchData(AbsXml absXml) {
        if (absXml != null && absXml.movie != null && absXml.movie.videoList != null && absXml.movie.videoList.size() > 0) {
            long elapsed = SystemClock.elapsedRealtime() - searchStartAt;
            for (Movie.Video video : absXml.movie.videoList) {
                if (video.sourceKey != null && !siteElapsed.containsKey(video.sourceKey)) {
                    siteElapsed.put(video.sourceKey, elapsed);
                }
                pendingVods.add(video);
                if (!resultVods.containsKey(video.sourceKey)) {
                    resultVods.put(video.sourceKey, new ArrayList<Movie.Video>());
                }
                resultVods.get(video.sourceKey).add(video);
            }
            if (searchDeadlineReached) {
                flushPending();
            } else if (!flushScheduled) {
                flushScheduled = true;
                rankHandler.postDelayed(flushRunnable, FLUSH_DELAY_MS);
            }
        }

        int count = allRunCount.decrementAndGet();
        if (count <= 0) {
            // 全部站点返回：立即刷新缓冲，再通知筛选器搜索阶段完成
            flushPending();
            rankHandler.removeCallbacks(deadlineRunnable);
            if (streamFilter != null) {
                streamFilter.markSearchCompleted();
            }
            if (searchAdapter.getData().size() <= 0 && (streamFilter == null || !streamFilter.isProbing())) {
                showEmpty();
            }
            cancel();
        }
    }

    /**
     * 把缓冲的结果提交给流式可播探测筛选器；若当前处于“全部源”模式则直接并入列表显示，
     * “仅看可播”模式下等待后台探测回调逐条流式展示。
     */
    private void flushPending() {
        rankHandler.removeCallbacks(flushRunnable);
        flushScheduled = false;
        if (pendingVods.isEmpty()) return;
        List<Movie.Video> incoming = SourceRanker.rank(pendingVods, SearchResultMerger.VIDEO_KEYS,
                searchTitle, SiteQualityStore.quality(), siteElapsed);
        pendingVods.clear();

        if (streamFilter != null) {
            streamFilter.submit(incoming, searchTitle);
            if (streamFilter.isPlayableOnly()) {
                return;
            }
        }
        String lastKey = null;
        for (Movie.Video v : incoming) {
            if (v.sourceKey != null && !v.sourceKey.equals(lastKey)) {
                lastKey = addWordAdapterIfNeed(v.sourceKey);
            }
        }
        if (SearchResultMerger.merge(searchAdapter, mGridView, incoming, searchTitle, siteElapsed)) {
            showSuccess();
            if (!isFilterMode) mGridView.setVisibility(View.VISIBLE);
        }
        if (streamFilter != null) {
            updateHeaderText(streamFilter.getPlayableCount(), streamFilter.getTotalCount(), streamFilter.isProbing());
        }
    }

    private void openDetail(Movie.Video video) {
        Bundle bundle = new Bundle();
        bundle.putString("id", video.id);
        bundle.putString("sourceKey", video.sourceKey);
        if (streamFilter != null) {
            PlayableStreamFilter.Preloaded pre = streamFilter.getPreloaded(video);
            if (pre != null) {
                String token = SmartSourceResolver.putPreloadedVod(pre.vodInfo);
                if (token != null) bundle.putString(SmartSourceResolver.EXTRA_PRELOADED_TOKEN, token);
                if (pre.matchedFlag != null) bundle.putString(SmartSourceResolver.EXTRA_PREFERRED_FLAG, pre.matchedFlag);
            }
        }
        // 同名的其他站点按当前排序交给播放页，播放失败时自动换源
        List<Movie.Video> all = new ArrayList<>(searchAdapter.getData());
        all.addAll(pendingVods);
        SearchResultMerger.putCandidates(bundle, all, video);
        jumpActivity(DetailActivity.class, bundle);
    }

    private void cancel() {
        OkGo.getInstance().cancelTag("search");
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        rankHandler.removeCallbacksAndMessages(null);
        if (streamFilter != null) {
            streamFilter.destroy();
        }
        cancel();
        try {
            if (searchExecutorService != null) {
                searchExecutorService.shutdownNow();
                searchExecutorService = null;
                JsLoader.stopAll();
            }
        } catch (Throwable th) {
            th.printStackTrace();
        }
        EventBus.getDefault().unregister(this);
    }
}
