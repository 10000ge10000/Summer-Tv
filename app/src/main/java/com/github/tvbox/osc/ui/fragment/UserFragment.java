package com.github.tvbox.osc.ui.fragment;

import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.chad.library.adapter.base.BaseQuickAdapter;
import com.github.tvbox.osc.ui.tv.Motion;
import com.github.tvbox.osc.ui.tv.Skeleton;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.base.BaseLazyFragment;
import com.github.tvbox.osc.bean.Movie;
import com.github.tvbox.osc.bean.VodInfo;
import com.github.tvbox.osc.cache.RoomDataManger;
import com.github.tvbox.osc.event.ServerEvent;
import com.github.tvbox.osc.ui.activity.*;
import com.github.tvbox.osc.ui.adapter.HomeHotVodAdapter;
import com.github.tvbox.osc.util.FastClickCheckUtil;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.UA;
import com.github.tvbox.osc.util.ImgUtil;
import com.github.tvbox.osc.util.DailyPick;
import com.bumptech.glide.Glide;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.lzy.okgo.OkGo;
import com.lzy.okgo.callback.AbsCallback;
import com.lzy.okgo.model.Response;
import com.orhanobut.hawk.Hawk;
import com.owen.tvrecyclerview.widget.TvRecyclerView;
import com.owen.tvrecyclerview.widget.V7GridLayoutManager;
import com.github.tvbox.osc.ui.tv.widget.LoadMoreView;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.util.ArrayList;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @author pj567
 * @date :2021/3/9
 * @description:
 */
public class UserFragment extends BaseLazyFragment implements View.OnClickListener {
    private View userHomeHeader;
    // 首屏 Hero：今日推荐（首页二次重构，快捷入口已移到 HomeActivity 左侧导航栏）
    private View heroBlock;
    private TextView heroEyebrow;
    private TextView heroTitle;
    private TextView heroMeta;
    private TextView heroIntro;
    private TextView heroPlay;
    private ImageView heroPoster;
    private Movie.Video heroVod;
    /** 今日精选候选：豆瓣热播第一页前 10 部，与“首页内容”设置无关 */
    private final List<Movie.Video> heroCandidates = new ArrayList<>();
    private static final String KEY_DAILY_PICK = "home_daily_pick";
    private static final String KEY_DAILY_PICK_HISTORY = "home_daily_pick_history";
    private String heroPosterUrl;
    /** 豆瓣详情缓存：豆瓣 id → {简介, 一行摘要}；值为 null 表示请求中或失败，避免重复请求 */
    private static final Map<String, String[]> HERO_DETAIL_CACHE = new HashMap<>();
    private String heroLabel = "";
    private boolean posterBrowsing = false;
    public static HomeHotVodAdapter homeHotVodAdapter;
    private List<Movie.Video> homeSourceRec;
    public static TvRecyclerView tvHotListForGrid;
    public static TvRecyclerView tvHotListForLine;
    private int doubanPage = 0;
    private boolean doubanLoading = false;
    private boolean doubanEnd = false;
    private static final int DOUBAN_PAGE_SIZE = 20;
    private static final String DOUBAN_MOBILE_UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36";

    public static UserFragment newInstance() {
        return new UserFragment();
    }

    public static UserFragment newInstance(List<Movie.Video> recVod) {
        return new UserFragment().setArguments(recVod);
    }

    public UserFragment setArguments(List<Movie.Video> recVod) {
        this.homeSourceRec = recVod;
        return this;
    }

    @Override
    public void onFragmentResume() {
        super.onFragmentResume();
        if (heroVod != null) {
            loadHeroPoster(heroVod.pic);
        }
        if (Hawk.get(HawkConfig.HOME_REC, 0) == 2) {
            List<VodInfo> allVodRecord = RoomDataManger.getAllVodRecord(20);
            List<Movie.Video> vodList = new ArrayList<>();
            for (VodInfo vodInfo : allVodRecord) {
                Movie.Video vod = new Movie.Video();
                vod.id = vodInfo.id;
                vod.sourceKey = vodInfo.sourceKey;
                vod.name = vodInfo.name;
                vod.pic = vodInfo.pic;
                if (vodInfo.playNote != null && !vodInfo.playNote.isEmpty())
                    vod.note = "上次看到" + vodInfo.playNote;
                vodList.add(vod);
            }
            homeHotVodAdapter.setNewData(vodList);
        }
    }

    private boolean canOpenDetail(Movie.Video vod) {
        return vod != null
                && vod.id != null
                && !vod.id.isEmpty()
                && !vod.id.startsWith("msearch:")
                && vod.sourceKey != null
                && ApiConfig.get().getSource(vod.sourceKey) != null;
    }

    @Override
    protected int getLayoutResID() {
        return R.layout.fragment_user;
    }
    private ImgUtil.Style style;
    @Override
    protected void init() {
        EventBus.getDefault().register(this);
        userHomeHeader = View.inflate(mContext, R.layout.layout_user_home_header, null);
        heroBlock = userHomeHeader.findViewById(R.id.heroBlock);
        heroEyebrow = userHomeHeader.findViewById(R.id.heroEyebrow);
        heroTitle = userHomeHeader.findViewById(R.id.heroTitle);
        heroMeta = userHomeHeader.findViewById(R.id.heroMeta);
        heroIntro = userHomeHeader.findViewById(R.id.heroIntro);
        heroPlay = userHomeHeader.findViewById(R.id.heroPlay);
        heroPoster = userHomeHeader.findViewById(R.id.heroPoster);
        heroPlay.setOnClickListener(this);
        heroPlay.setOnFocusChangeListener(focusChangeListener);
        // 向下落到正下方的海报列；向上由 nextFocusUp 回到分类 Tab
        heroPlay.setOnKeyListener(headerEntryKeyListener);
        tvHotListForLine = findViewById(R.id.tvHotListForLine);
        tvHotListForGrid = findViewById(R.id.tvHotListForGrid);
        tvHotListForGrid.setHasFixedSize(true);
        // Hero 占满列表可见高度：列表高度随顶栏显隐变化，每次变化后重新计算
        tvHotListForGrid.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (b - t != ob - ot) {
                v.post(this::applyHeroHeight);
            }
        });
        // 每行 4 张：卡片与分类页（item_grid，vs_214×vs_280）同一尺寸（第四轮用户反馈）
        int spanCount = 4;
        if (Hawk.get(HawkConfig.HOME_REC, 0) == 1 && homeSourceRec!=null) {
            style=ImgUtil.initStyle();
        }
        if(style!=null && Hawk.get(HawkConfig.HOME_REC, 0) == 1) {
            spanCount=ImgUtil.spanCountByStyle(style,spanCount);
        }
        tvHotListForGrid.setLayoutManager(new V7GridLayoutManager(this.mContext, spanCount)); 
        String tvRate="";
        if(Hawk.get(HawkConfig.HOME_REC, 0) == 0){
            tvRate="豆瓣热播";
        }else if(Hawk.get(HawkConfig.HOME_REC, 0) == 1){
          tvRate= homeSourceRec != null ? "站点推荐" : "豆瓣热播";
        }
        homeHotVodAdapter = new HomeHotVodAdapter(style,tvRate);
        heroLabel = tvRate;
        homeHotVodAdapter.registerAdapterDataObserver(heroObserver);
        attachUserHomeHeader(homeHotVodAdapter);
        homeHotVodAdapter.setLoadMoreView(new LoadMoreView());
        homeHotVodAdapter.setOnLoadMoreListener(new BaseQuickAdapter.RequestLoadMoreListener() {
            @Override
            public void onLoadMoreRequested() {
                if (Hawk.get(HawkConfig.HOME_REC, 0) == 0) {
                    loadDoubanHot(false);
                } else {
                    homeHotVodAdapter.loadMoreEnd();
                }
            }
        }, tvHotListForGrid);
        homeHotVodAdapter.setOnItemClickListener(new BaseQuickAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(BaseQuickAdapter adapter, View view, int position) {
                Movie.Video vod = ((Movie.Video) adapter.getItem(position));
                if (vod == null) {
                    return;
                }

                // takagen99: CHeck if in Delete Mode
                if ((vod.id != null && !vod.id.isEmpty()) && (Hawk.get(HawkConfig.HOME_REC, 0) == 2) && HawkConfig.hotVodDelete) {
                    if (vod.sourceKey == null || ApiConfig.get().getSource(vod.sourceKey) == null) {
                        return;
                    }
                    homeHotVodAdapter.remove(position);
                    VodInfo vodInfo = RoomDataManger.getVodInfo(vod.sourceKey, vod.id);
                    RoomDataManger.deleteVodRecord(vod.sourceKey, vodInfo);
                    Toast.makeText(mContext, getString(R.string.hm_hist_del), Toast.LENGTH_SHORT).show();
                } else {
                    openVod(vod);
                }
            }
        });
        // takagen99 : Long press to trigger Delete Mode for VOD History on Home Page
        homeHotVodAdapter.setOnItemLongClickListener(new BaseQuickAdapter.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(BaseQuickAdapter adapter, View view, int position) {
                if (ApiConfig.get().getSourceBeanList().isEmpty())
                    return false;
                Movie.Video vod = ((Movie.Video) adapter.getItem(position));
                if (vod == null) {
                    return false;
                }
                // Additional Check if : Home Rec 0=豆瓣, 1=推荐, 2=历史
                if ((vod.id != null && !vod.id.isEmpty()) && (Hawk.get(HawkConfig.HOME_REC, 0) == 2)) {
                    if (vod.sourceKey == null || ApiConfig.get().getSource(vod.sourceKey) == null) {
                        return false;
                    }
                    HawkConfig.hotVodDelete = !HawkConfig.hotVodDelete;
                    homeHotVodAdapter.notifyDataSetChanged();
                } else {
                    Intent newIntent = new Intent(mContext, FastSearchActivity.class);
                    newIntent.putExtra("title", vod.name);
                    newIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                    mActivity.startActivity(newIntent);
                }
                return true;
            }
        });
        
        // Grid View
        tvHotListForGrid.setOnItemListener(new TvRecyclerView.OnItemListener() {
            @Override
            public void onItemPreSelected(TvRecyclerView parent, View itemView, int position) {
                if (itemView != null) {
                    Motion.unfocus(itemView);
                }
            }

            @Override
            public void onItemSelected(TvRecyclerView parent, View itemView, int position) {
                if (isHeaderPosition(position)) {
                    return; // 快捷入口所在的 header 行：不放大、不进入海报浏览，否则 header 会被隐藏、焦点被挤到海报上
                }
                if (itemView != null) {
                    Motion.focusCard(itemView);
                }
                preloadMoreDouban(position);
                showAmbientAt(position);
                if (getActivity() instanceof HomeActivity) {
                    HomeActivity home = (HomeActivity) getActivity();
                    if (parent != null && (parent.hasFocus() || parent.isFocused())) {
                        home.setPosterBrowseMode(true);
                    }
                }
            }

            @Override
            public void onItemClick(TvRecyclerView parent, View itemView, int position) {

            }
        });
        tvHotListForGrid.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus && getActivity() instanceof HomeActivity && !isHeaderFocused((RecyclerView) v)) {
                ((HomeActivity) getActivity()).setPosterBrowseMode(true);
            }
        });
        tvHotListForGrid.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
                super.onScrollStateChanged(recyclerView, newState);
                if (getActivity() instanceof HomeActivity) {
                    if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                        ((HomeActivity) getActivity()).setPosterBrowseMode(true);
                    } else if (newState == RecyclerView.SCROLL_STATE_IDLE && !recyclerView.canScrollVertically(-1)) {
                        ((HomeActivity) getActivity()).setPosterBrowseMode(false);
                    }
                }
            }

            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                super.onScrolled(recyclerView, dx, dy);
                // 按键触发的平滑滚动和手指滑动都会走到这里，统一按 Hero 的位置换算顶栏收起程度
                syncTopBar();
            }
        });
        tvHotListForGrid.setOnInBorderKeyEventListener(new TvRecyclerView.OnInBorderKeyEventListener() {
            @Override
            public boolean onInBorderKeyEvent(int direction, View focused) {
                // 向左进入左侧导航栏；海报第一行向上回到 Hero 的播放按钮，已在 Hero 时回到分类 Tab
                if (!(getActivity() instanceof HomeActivity)) {
                    return false;
                }
                HomeActivity home = (HomeActivity) getActivity();
                if (direction == View.FOCUS_LEFT) {
                    return home.focusNavRail();
                }
                if (direction == View.FOCUS_UP && !isHeaderFocused(currentHotList()) && focusHomeHeader()) {
                    return true;
                }
                if (direction == View.FOCUS_UP) {
                    home.exitPosterBrowseAndFocusCategory();
                    return true;
                }
                return false;
            }
        });
        tvHotListForGrid.setAdapter(homeHotVodAdapter);
        // Line View
        tvHotListForLine.setOnItemListener(new TvRecyclerView.OnItemListener() {
            @Override
            public void onItemPreSelected(TvRecyclerView parent, View itemView, int position) {
                if (itemView != null) {
                    Motion.unfocus(itemView);
                }
            }

            @Override
            public void onItemSelected(TvRecyclerView parent, View itemView, int position) {
                if (isHeaderPosition(position)) {
                    return; // 快捷入口所在的 header 行：不放大、不进入海报浏览，否则 header 会被隐藏、焦点被挤到海报上
                }
                if (itemView != null) {
                    Motion.focusCard(itemView);
                }
                preloadMoreDouban(position);
                showAmbientAt(position);
                if (getActivity() instanceof HomeActivity) {
                    HomeActivity home = (HomeActivity) getActivity();
                    if (parent != null && (parent.hasFocus() || parent.isFocused())) {
                        home.setPosterBrowseMode(true);
                    }
                }
            }

            @Override
            public void onItemClick(TvRecyclerView parent, View itemView, int position) {

            }
        });
        tvHotListForLine.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus && getActivity() instanceof HomeActivity && !isHeaderFocused((RecyclerView) v)) {
                ((HomeActivity) getActivity()).setPosterBrowseMode(true);
            }
        });
        tvHotListForLine.setOnInBorderKeyEventListener(new TvRecyclerView.OnInBorderKeyEventListener() {
            @Override
            public boolean onInBorderKeyEvent(int direction, View focused) {
                // 向左进入左侧导航栏；海报第一行向上回到 Hero 的播放按钮，已在 Hero 时回到分类 Tab
                if (!(getActivity() instanceof HomeActivity)) {
                    return false;
                }
                HomeActivity home = (HomeActivity) getActivity();
                if (direction == View.FOCUS_LEFT) {
                    return home.focusNavRail();
                }
                if (direction == View.FOCUS_UP && !isHeaderFocused(currentHotList()) && focusHomeHeader()) {
                    return true;
                }
                if (direction == View.FOCUS_UP) {
                    home.exitPosterBrowseAndFocusCategory();
                    return true;
                }
                return false;
            }
        });
        tvHotListForLine.setAdapter(homeHotVodAdapter);

        initHomeHotVod(homeHotVodAdapter);

        // 首页统一网格样式：今日精选要整屏展示，横向单行样式放不下（样式切换入口早已隐藏）
        if (true) {
            tvHotListForGrid.setVisibility(View.VISIBLE);
            tvHotListForLine.setVisibility(View.GONE);
        } else {
            tvHotListForGrid.setVisibility(View.GONE);
            tvHotListForLine.setVisibility(View.VISIBLE);
        }
    }

    /** 海报浏览只是一个标记：Hero 作为列表第一项随列表自然滚走，不再隐藏（隐藏会让列表瞬间上移一屏） */
    public void setPosterBrowseMode(boolean immersive) {
        posterBrowsing = immersive;
        if (!immersive) {
            updateHero();
        }
    }

    /** 按 Hero 的滚动位置换算首页顶栏的收起程度，交给 HomeActivity 做平移和淡出 */
    public void syncTopBar() {
        if (!isAdded() || !(getActivity() instanceof HomeActivity)) {
            return;
        }
        HomeActivity home = (HomeActivity) getActivity();
        if (!home.isCurrentPage(this)) {
            return;
        }
        RecyclerView list = currentHotList();
        RecyclerView.LayoutManager lm = list == null ? null : list.getLayoutManager();
        View heroRow = lm == null ? null : lm.findViewByPosition(0);
        float progress;
        if (heroRow == null) {
            progress = list == null || !list.canScrollVertically(-1) ? 0f : 1f;
        } else {
            int range = getResources().getDimensionPixelSize(R.dimen.st_top_bar_offset);
            progress = range <= 0 ? 0f : -heroRow.getTop() / (float) range;
        }
        home.setTopBarProgress(progress);
    }

    /** homeHotVodAdapter 是静态字段，观察者必须在 onDestroy 注销，否则会一直持有已销毁的 Fragment */
    private final RecyclerView.AdapterDataObserver heroObserver = new RecyclerView.AdapterDataObserver() {
        @Override
        public void onChanged() {
            updateHero();
        }

        @Override
        public void onItemRangeInserted(int positionStart, int itemCount) {
            updateHero();
        }

        @Override
        public void onItemRangeRemoved(int positionStart, int itemCount) {
            updateHero();
        }
    };

    /** Hero 播放按钮按下键：落到第一行海报（按按钮所在列对齐），不交给 TvRecyclerView 自行挑选 */
    private final View.OnKeyListener headerEntryKeyListener = (v, keyCode, event) -> {
        if (keyCode != android.view.KeyEvent.KEYCODE_DPAD_DOWN
                || event.getAction() != android.view.KeyEvent.ACTION_DOWN) {
            return false;
        }
        if (!(v.getParent() instanceof ViewGroup) || homeHotVodAdapter == null
                || homeHotVodAdapter.getData().isEmpty()) {
            return false;
        }
        ViewGroup row = (ViewGroup) v.getParent();
        int column = 0;
        for (int i = 0; i < row.getChildCount() && row.getChildAt(i) != v; i++) {
            if (row.getChildAt(i).getVisibility() == View.VISIBLE) {
                column++;
            }
        }
        RecyclerView list = currentHotList();
        if (list == null) {
            return false;
        }
        int span = list.getLayoutManager() instanceof androidx.recyclerview.widget.GridLayoutManager
                ? ((androidx.recyclerview.widget.GridLayoutManager) list.getLayoutManager()).getSpanCount() : 1;
        int index = Math.min(Math.min(column, span - 1), homeHotVodAdapter.getData().size() - 1);
        int position = homeHotVodAdapter.getHeaderLayoutCount() + index;
        View target = list.getLayoutManager() == null ? null
                : list.getLayoutManager().findViewByPosition(position);
        if (target == null) {
            // Hero 占满整屏时第一行海报可能还没被布局：先平滑滚过去，滚动结束再聚焦
            focusAfterScroll(list, position);
            return true;
        }
        target.requestFocus();
        return true;
    };

    private RecyclerView currentHotList() {
        if (tvHotListForGrid != null && tvHotListForGrid.getVisibility() == View.VISIBLE) {
            return tvHotListForGrid;
        }
        return tvHotListForLine;
    }

    private boolean isHeaderPosition(int position) {
        return homeHotVodAdapter != null && position >= 0 && position < homeHotVodAdapter.getHeaderLayoutCount();
    }

    /** BRVAH 会把 header 包在自己的容器里，所以按条目位置判断焦点是否在 header 行 */
    private boolean isHeaderFocused(RecyclerView list) {
        View child = list == null ? null : list.getFocusedChild();
        return child != null && isHeaderPosition(list.getChildAdapterPosition(child));
    }

    /** Hero 的播放按钮；Hero 没有内容（列表为空）时返回 null */
    private View firstHeaderEntry() {
        if (heroPlay == null || heroBlock == null || heroBlock.getVisibility() != View.VISIBLE) {
            return null;
        }
        return heroPlay;
    }

    /**
     * 网格模式下 Hero 占满列表可见高度（整屏），海报墙从下一屏开始；
     * 单行模式（横向列表）保持内容自身高度。
     */
    private void applyHeroHeight() {
        if (userHomeHeader == null || !isAdded()) {
            return;
        }
        ViewGroup.LayoutParams lp = userHomeHeader.getLayoutParams();
        if (lp == null) {
            return;
        }
        int target = ViewGroup.LayoutParams.WRAP_CONTENT;
        RecyclerView list = currentHotList();
        if (list != null && list == tvHotListForGrid && list.getHeight() > 0) {
            int h = list.getHeight() - list.getPaddingTop();
            if (h > 0) {
                target = h;
            }
        }
        if (lp.height != target) {
            lp.height = target;
            userHomeHeader.setLayoutParams(lp);
        }
    }

    /** 列表平滑回到顶部，让今日精选重新露出来 */
    public void scrollToTop() {
        RecyclerView list = currentHotList();
        if (list != null) {
            list.stopScroll();
            list.smoothScrollToPosition(0);
        }
    }

    /** 平滑滚动到 position，滚动停止后把焦点交给该位置的条目 */
    private void focusAfterScroll(RecyclerView list, int position) {
        // 已在顶部（或无法再滚）时不会产生滚动回调，监听会一直挂着并在之后的滚动里抢焦点
        if (position == 0 && !list.canScrollVertically(-1)) {
            View target = firstHeaderEntry();
            if (target != null) target.requestFocus();
            return;
        }
        list.smoothScrollToPosition(position);
        list.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView rv, int newState) {
                if (newState != RecyclerView.SCROLL_STATE_IDLE) {
                    return;
                }
                rv.removeOnScrollListener(this);
                RecyclerView.LayoutManager lm = rv.getLayoutManager();
                View target = position == 0 ? firstHeaderEntry() : (lm == null ? null : lm.findViewByPosition(position));
                if (target != null && target.isAttachedToWindow()) {
                    target.requestFocus();
                }
            }
        });
    }

    /**
     * 把焦点交给今日精选的播放按钮：Hero 已在屏幕上就直接聚焦（TvRecyclerView 会平滑滚到它），
     * 已被回收则先平滑回顶再聚焦。
     */
    public boolean focusHomeHeader() {
        View entry = firstHeaderEntry();
        RecyclerView list = currentHotList();
        if (entry == null || list == null || userHomeHeader == null) {
            return false;
        }
        if (getActivity() instanceof HomeActivity) {
            ((HomeActivity) getActivity()).setPosterBrowseMode(false);
        } else {
            setPosterBrowseMode(false);
        }
        if (entry.isAttachedToWindow() && entry.isShown()) {
            entry.requestFocus();
            return true;
        }
        list.stopScroll();
        focusAfterScroll(list, 0);
        return true;
    }

    /**
     * 今日精选固定取自豆瓣热播（每天一换，见 {@link #pickDailyHero()}），与“首页内容”设置无关；
     * 豆瓣取不到时用海报墙第一部顶替。不在海报浏览时，背景也跟随它。
     */
    private void updateHero() {
        if (heroBlock == null || homeHotVodAdapter == null || !isAdded()) {
            return;
        }
        Movie.Video first = heroCandidates.isEmpty() ? null : pickDailyHero();
        boolean fromDouban = first != null;
        if (first == null) {
            List<Movie.Video> data = homeHotVodAdapter.getData();
            first = data == null || data.isEmpty() ? null : data.get(0);
        }
        if (first == null || TextUtils.isEmpty(first.name)) {
            heroBlock.setVisibility(View.GONE);
            return;
        }
        heroBlock.setVisibility(View.VISIBLE);
        if (getActivity() instanceof HomeActivity) {
            ((HomeActivity) getActivity()).onHomeContentReady();
        }
        heroVod = first;
        String featured = getString(R.string.hm_hero_featured);
        if (fromDouban) {
            heroEyebrow.setText(featured + " · " + getString(R.string.hm_hero_source_douban));
        } else if (Hawk.get(HawkConfig.HOME_REC, 0) == 2) {
            heroEyebrow.setText(R.string.hm_hero_continue);
        } else {
            heroEyebrow.setText(TextUtils.isEmpty(heroLabel) ? featured : featured + " · " + heroLabel);
        }
        heroTitle.setText(first.name);
        bindHeroDetail(first);
        loadHeroDetail(first);
        loadHeroPoster(first.pic);
        applyHeroHeight();
        if (!posterBrowsing) {
            showAmbient(first.pic);
        }
    }

    /** 信息行与简介：豆瓣条目优先用详情接口的摘要和简介，未取到前先用列表自带的评分 / 导演 / 主演 */
    private void bindHeroDetail(Movie.Video vod) {
        String[] detail = TextUtils.isEmpty(vod.doubanId) ? null : HERO_DETAIL_CACHE.get(vod.doubanId);
        StringBuilder meta = new StringBuilder();
        if (!TextUtils.isEmpty(vod.doubanId)) {
            if (!TextUtils.isEmpty(vod.note)) {
                meta.append(getString(R.string.hm_hero_douban, vod.note));
            }
            String extra = detail != null && !TextUtils.isEmpty(detail[1]) ? detail[1]
                    : joinNonEmpty(" / ", vod.director, vod.actor);
            if (!TextUtils.isEmpty(extra)) {
                meta.append(meta.length() > 0 ? "  ·  " : "").append(extra);
            }
        } else if (!TextUtils.isEmpty(vod.note)) {
            meta.append(vod.note);
        }
        heroMeta.setText(meta);
        heroMeta.setVisibility(meta.length() > 0 ? View.VISIBLE : View.GONE);

        String intro = detail != null ? detail[0] : null;
        if (TextUtils.isEmpty(intro) && !TextUtils.isEmpty(vod.des)) {
            intro = vod.des.replaceAll("<[^>]+>", "").trim();
        }
        if (intro != null) {
            intro = intro.replaceAll("\\s+", " ").trim();
        }
        heroIntro.setText(intro);
        heroIntro.setVisibility(TextUtils.isEmpty(intro) ? View.GONE : View.VISIBLE);
    }

    private static String joinNonEmpty(String sep, String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (!TextUtils.isEmpty(part)) {
                sb.append(sb.length() > 0 ? sep : "").append(part);
            }
        }
        return sb.toString();
    }

    /** 豆瓣列表接口没有简介，按 id 补取一次详情（同一进程内缓存） */
    private void loadHeroDetail(Movie.Video vod) {
        String id = vod.doubanId;
        if (TextUtils.isEmpty(id) || HERO_DETAIL_CACHE.containsKey(id)) {
            return;
        }
        HERO_DETAIL_CACHE.put(id, null);
        OkGo.<String>get("https://m.douban.com/rexxar/api/v2/movie/" + id + "?for_mobile=1")
                .tag(this)
                .headers("User-Agent", DOUBAN_MOBILE_UA)
                .headers("Referer", "https://m.douban.com/movie/")
                .execute(new AbsCallback<String>() {
                    @Override
                    public void onSuccess(Response<String> response) {
                        try {
                            JsonObject obj = new Gson().fromJson(response.body(), JsonObject.class);
                            String intro = obj.has("intro") && !obj.get("intro").isJsonNull() ? obj.get("intro").getAsString() : "";
                            String sub = obj.has("card_subtitle") && !obj.get("card_subtitle").isJsonNull() ? obj.get("card_subtitle").getAsString() : "";
                            HERO_DETAIL_CACHE.put(id, new String[]{intro, sub});
                        } catch (Throwable ignored) {
                            return; // 解析失败就保持列表里的信息
                        }
                        if (isAdded() && heroVod != null && id.equals(heroVod.doubanId)) {
                            bindHeroDetail(heroVod);
                        }
                    }

                    @Override
                    public String convertResponse(okhttp3.Response response) throws Throwable {
                        return response.body().string();
                    }
                });
    }

    /** 右侧竖版海报：豆瓣封面换成中尺寸，首屏放大后不糊 */
    private void loadHeroPoster(String pic) {
        if (heroPoster == null || TextUtils.isEmpty(pic) || mActivity == null || mActivity.isFinishing()) {
            return;
        }
        // 首页切到较远的分类时视图会被销毁重建，图片可能已被清掉，所以“同一张图”时还要确认确实显示着
        if (pic.equals(heroPosterUrl) && heroPoster.getDrawable() != null) {
            return;
        }
        heroPosterUrl = pic;
        Object model = ImgUtil.getUrl(pic.replace("/s_ratio_poster/", "/m_ratio_poster/"));
        if (model == null) {
            return;
        }
        Glide.with(mActivity).load(model).centerCrop().into(heroPoster);
    }

    /** 打开一部影片：豆瓣条目走聚合搜索，能直接打开详情的进详情页，其余走搜索 */
    private void openVod(Movie.Video vod) {
        if (vod.id != null && !vod.id.isEmpty()) {
            Bundle bundle = new Bundle();
            bundle.putString("id", vod.id);
            bundle.putString("sourceKey", vod.sourceKey);
            if (vod.id.startsWith("msearch:")) {
                bundle.putString("title", vod.name);
                jumpActivity(FastSearchActivity.class, bundle);
                return;
            }
            if (canOpenDetail(vod)) {
                jumpActivity(DetailActivity.class, bundle);
                return;
            }
        }
        Intent newIntent = new Intent(mContext, Hawk.get(HawkConfig.FAST_SEARCH_MODE, false) ? FastSearchActivity.class : SearchActivity.class);
        newIntent.putExtra("title", vod.name);
        newIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        mActivity.startActivity(newIntent);
    }

    /** position 是 RecyclerView 中的位置，包含 header，需要换算成数据下标 */
    private void showAmbientAt(int position) {
        if (homeHotVodAdapter == null) {
            return;
        }
        Movie.Video vod = homeHotVodAdapter.getItem(position - homeHotVodAdapter.getHeaderLayoutCount());
        if (vod != null) {
            showAmbient(vod.pic);
        }
    }

    private void showAmbient(String pic) {
        if (isAdded() && getActivity() instanceof HomeActivity) {
            ((HomeActivity) getActivity()).showAmbient(pic);
        }
    }

    private void attachUserHomeHeader(HomeHotVodAdapter adapter) {
        if (userHomeHeader.getParent() instanceof ViewGroup) {
            ((ViewGroup) userHomeHeader.getParent()).removeView(userHomeHeader);
        }
        adapter.addHeaderView(userHomeHeader);
    }

    private void initHomeHotVod(HomeHotVodAdapter adapter) {
        if (Hawk.get(HawkConfig.HOME_REC, 0) == 1) {
            if (homeSourceRec != null) {
                adapter.setNewData(homeSourceRec);
            }
            adapter.setEnableLoadMore(false);
            loadHeroCandidates();
            return;
        } else if (Hawk.get(HawkConfig.HOME_REC, 0) == 2) {
            adapter.setEnableLoadMore(false);
            loadHeroCandidates();
            return;
        }
        adapter.setEnableLoadMore(true);
        doubanPage = 0;
        doubanEnd = false;
        showHomeSkeleton(adapter);
        loadDoubanHot(true);
    }

    /** 豆瓣热播加载期间，在 header 下方显示海报骨架屏；仅网格样式（横向单行样式不适用） */
    private void showHomeSkeleton(HomeHotVodAdapter adapter) {
        // 此时列表可见性还没按样式设置，直接读样式配置（与 init 末尾的判断一致）
        if (!adapter.getData().isEmpty()) {
            return;
        }
        View skeleton = View.inflate(mContext, R.layout.layout_poster_skeleton_rows, null);
        Skeleton.pulse(skeleton);
        adapter.setHeaderAndEmpty(true);
        adapter.isUseEmpty(true);
        adapter.setEmptyView(skeleton);
    }

    /** 加载结束仍然没有数据时撤掉骨架屏，避免一直“加载中” */
    private void stopHomeSkeleton() {
        if (homeHotVodAdapter != null && homeHotVodAdapter.getData().isEmpty()
                && homeHotVodAdapter.getEmptyViewCount() > 0) {
            homeHotVodAdapter.isUseEmpty(false);
            homeHotVodAdapter.notifyDataSetChanged();
        }
    }

    private interface DoubanPageCallback {
        void onJson(String json);

        void onError();
    }

    /** 豆瓣热播一页：同一天内直接用缓存（缓存键按 yyyyMMdd，避免 2026-1-11 与 2026-11-1 撞键） */
    private void requestDoubanPage(int start, DoubanPageCallback callback) {
        String today = new SimpleDateFormat("yyyyMMdd", Locale.CHINA).format(new Date()) + "_" + start;
        String requestKey = "home_hot_" + start;
        String requestDayKey = "home_hot_day_" + start;
        if (today.equals(Hawk.get(requestDayKey, ""))) {
            String json = Hawk.get(requestKey, "");
            if (!json.isEmpty()) {
                callback.onJson(json);
                return;
            }
        }
        int year = Calendar.getInstance().get(Calendar.YEAR);
        String doubanHotURL = "https://movie.douban.com/j/new_search_subjects?sort=U&range=0,10&tags=&playable=1&start=" + start + "&year_range=" + year + "," + year;
        OkGo.<String>get(doubanHotURL).tag(this).headers("User-Agent", UA.random()).execute(new AbsCallback<String>() {
            @Override
            public void onSuccess(Response<String> response) {
                String netJson = response.body();
                Hawk.put(requestDayKey, today);
                Hawk.put(requestKey, netJson);
                if (isAdded()) {
                    callback.onJson(netJson);
                }
            }

            @Override
            public void onError(Response<String> response) {
                super.onError(response);
                if (isAdded()) {
                    callback.onError();
                }
            }

            @Override
            public String convertResponse(okhttp3.Response response) throws Throwable {
                return response.body().string();
            }
        });
    }

    private void loadDoubanHot(boolean refresh) {
        if (doubanLoading || doubanEnd) {
            return;
        }
        doubanLoading = true;
        int start = refresh ? 0 : doubanPage * DOUBAN_PAGE_SIZE;
        try {
            requestDoubanPage(start, new DoubanPageCallback() {
                @Override
                public void onJson(String json) {
                    applyDoubanData(loadHots(json), refresh);
                }

                @Override
                public void onError() {
                    doubanLoading = false;
                    if (refresh) {
                        applyHomeSourceFallback();
                        stopHomeSkeleton();
                    }
                    homeHotVodAdapter.loadMoreComplete();
                }
            });
        } catch (Throwable th) {
            doubanLoading = false;
            if (refresh) {
                applyHomeSourceFallback();
                stopHomeSkeleton();
            }
            homeHotVodAdapter.loadMoreComplete();
            th.printStackTrace();
        }
    }

    /** 首页内容不是豆瓣热播时，单独取豆瓣第一页作为今日精选候选 */
    private void loadHeroCandidates() {
        try {
            requestDoubanPage(0, new DoubanPageCallback() {
                @Override
                public void onJson(String json) {
                    setHeroCandidates(loadHots(json));
                }

                @Override
                public void onError() {
                    // 取不到就用海报墙第一部顶替
                }
            });
        } catch (Throwable ignored) {
        }
    }

    private void setHeroCandidates(List<Movie.Video> videos) {
        heroCandidates.clear();
        if (videos != null) {
            heroCandidates.addAll(videos.subList(0, Math.min(DailyPick.POOL_SIZE, videos.size())));
        }
        updateHero();
    }

    /** 今日精选：每天从候选里选一部，同一天不变，最近 7 天不重复 */
    private Movie.Video pickDailyHero() {
        List<String> names = new ArrayList<>();
        for (Movie.Video v : heroCandidates) {
            names.add(v.name == null ? "" : v.name);
        }
        String today = new SimpleDateFormat("yyyyMMdd", Locale.CHINA).format(new Date());
        String saved = Hawk.get(KEY_DAILY_PICK, "");
        String savedDay = saved.contains("|") ? saved.substring(0, saved.indexOf('|')) : "";
        String savedName = saved.contains("|") ? saved.substring(saved.indexOf('|') + 1) : "";
        // 历史格式：yyyyMMdd|片名，用换行分隔，只保留最近几天
        List<String> history = new ArrayList<>();
        for (String line : Hawk.get(KEY_DAILY_PICK_HISTORY, "").split("\n")) {
            if (line.contains("|")) {
                history.add(line);
            }
        }
        List<String> recent = new ArrayList<>();
        for (String line : history) {
            String day = line.substring(0, line.indexOf('|'));
            if (!day.equals(today)) {
                recent.add(line.substring(line.indexOf('|') + 1));
            }
        }
        int idx = DailyPick.pick(names, today, savedDay, savedName, recent);
        if (idx < 0) {
            return null;
        }
        String name = names.get(idx);
        if (!today.equals(savedDay) || !name.equals(savedName)) {
            Hawk.put(KEY_DAILY_PICK, today + "|" + name);
            List<String> kept = new ArrayList<>();
            for (String line : history) {
                if (!line.startsWith(today + "|")) {
                    kept.add(line);
                }
            }
            kept.add(today + "|" + name);
            int from = Math.max(0, kept.size() - DailyPick.RECENT_DAYS);
            Hawk.put(KEY_DAILY_PICK_HISTORY, TextUtils.join("\n", kept.subList(from, kept.size())));
        }
        return heroCandidates.get(idx);
    }

    private void preloadMoreDouban(int position) {
        if (Hawk.get(HawkConfig.HOME_REC, 0) != 0 || doubanLoading || doubanEnd || homeHotVodAdapter == null) {
            return;
        }
        int count = homeHotVodAdapter.getData() == null ? 0 : homeHotVodAdapter.getData().size();
        if (count > 0 && position >= count - 6) {
            loadDoubanHot(false);
        }
    }

    private void applyHomeSourceFallback() {
        if (homeHotVodAdapter == null || homeHotVodAdapter.getData().size() > 0) {
            return;
        }
        if (homeSourceRec != null && !homeSourceRec.isEmpty()) {
            homeHotVodAdapter.setEnableLoadMore(false);
            homeHotVodAdapter.setNewData(homeSourceRec);
            Toast.makeText(mContext, "豆瓣热播加载失败，已显示当前源推荐", Toast.LENGTH_SHORT).show();
        }
    }

    private void applyDoubanData(ArrayList<Movie.Video> videos, boolean refresh) {
        doubanLoading = false;
        if (videos == null || videos.isEmpty()) {
            doubanEnd = true;
            homeHotVodAdapter.loadMoreEnd();
            if (refresh) {
                stopHomeSkeleton();
            }
            return;
        }
        if (refresh) {
            heroCandidates.clear();
            heroCandidates.addAll(videos.subList(0, Math.min(DailyPick.POOL_SIZE, videos.size())));
            homeHotVodAdapter.setNewData(videos);
            doubanPage = 1;
        } else {
            homeHotVodAdapter.addData(videos);
            doubanPage++;
        }
        homeHotVodAdapter.loadMoreComplete();
    }

    private ArrayList<Movie.Video> loadHots(String json) {
        ArrayList<Movie.Video> result = new ArrayList<>();
        try {
            JsonObject infoJson = new Gson().fromJson(json, JsonObject.class);
            JsonArray array = infoJson.getAsJsonArray("data");
            for (JsonElement ele : array) {
                JsonObject obj = (JsonObject) ele;
                Movie.Video vod = new Movie.Video();
                vod.name = obj.get("title").getAsString();
                vod.id = "msearch:" + vod.name;
                vod.note = obj.get("rate").getAsString();
                if (obj.has("id")) {
                    vod.doubanId = obj.get("id").getAsString();
                }
                vod.director = joinArray(obj, "directors", 2);
                vod.actor = joinArray(obj, "casts", 3);
                vod.pic = obj.get("cover").getAsString() + "@User-Agent=" + UA.random() + "@Referer=https://www.douban.com/";                
                result.add(vod);
            }
        } catch (Throwable th) {

        }
        return result;
    }

    private static String joinArray(JsonObject obj, String key, int limit) {
        if (!obj.has(key) || !obj.get(key).isJsonArray()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        JsonArray arr = obj.getAsJsonArray(key);
        for (int i = 0; i < arr.size() && i < limit; i++) {
            sb.append(i > 0 ? " " : "").append(arr.get(i).getAsString());
        }
        return sb.toString();
    }

    private final View.OnFocusChangeListener focusChangeListener = new View.OnFocusChangeListener() {
        @Override
        public void onFocusChange(View v, boolean hasFocus) {
            if (hasFocus)
                Motion.focusButton(v);
            else
                Motion.unfocus(v);
        }
    };

    @Override
    public void onClick(View v) {

        // takagen99: Remove Delete Mode
        HawkConfig.hotVodDelete = false;

        FastClickCheckUtil.check(v);
        if (v.getId() == R.id.heroPlay && heroVod != null) {
            openVod(heroVod);
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void server(ServerEvent event) {
        if (event.type == ServerEvent.SERVER_CONNECTION) {
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        EventBus.getDefault().unregister(this);
        OkGo.getInstance().cancelTag(this);
        // init() 只执行一次且 rootView 会被复用，所以观察者在 Fragment 销毁时才注销，不放在 onDestroyView
        if (homeHotVodAdapter != null) {
            try {
                homeHotVodAdapter.unregisterAdapterDataObserver(heroObserver);
            } catch (IllegalStateException ignored) {
                // 未注册过（init 尚未执行），或静态适配器已被新实例替换
            }
        }
    }
}
