package com.github.tvbox.osc.ui.fragment;
import android.content.res.TypedArray;
import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.core.content.ContextCompat;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;
import com.blankj.utilcode.util.GsonUtils;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.chad.library.adapter.base.BaseQuickAdapter;
import com.github.tvbox.osc.ui.tv.Motion;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.base.BaseLazyFragment;
import com.github.tvbox.osc.bean.AbsXml;
import com.github.tvbox.osc.bean.Movie;
import com.github.tvbox.osc.bean.MovieSort;
import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.event.RefreshEvent;
import com.github.tvbox.osc.ui.activity.DetailActivity;
import com.github.tvbox.osc.ui.activity.HomeActivity;
import com.github.tvbox.osc.ui.activity.FastSearchActivity;
import com.github.tvbox.osc.ui.activity.SearchActivity;
import com.github.tvbox.osc.ui.adapter.GridAdapter;
import com.github.tvbox.osc.ui.adapter.GridFilterKVAdapter;
import com.github.tvbox.osc.ui.adapter.SubCategoryAdapter;
import com.github.tvbox.osc.ui.dialog.GridFilterDialog;
import com.github.tvbox.osc.ui.tv.widget.LoadMoreView;
import com.github.tvbox.osc.util.ImgUtil;
import com.github.tvbox.osc.util.FastClickCheckUtil;
import com.github.tvbox.osc.util.LOG;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.MD5;
import com.github.tvbox.osc.util.CategoryOrganizer;
import com.github.tvbox.osc.viewmodel.SourceViewModel;
import com.orhanobut.hawk.Hawk;
import com.owen.tvrecyclerview.widget.TvRecyclerView;
import com.owen.tvrecyclerview.widget.V7GridLayoutManager;
import com.owen.tvrecyclerview.widget.V7LinearLayoutManager;

import java.util.ArrayList;
import java.util.List;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.greenrobot.eventbus.EventBus;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.Stack;

/**
 * @author pj567
 * @date :2020/12/21
 * @description:
 */
public class GridFragment extends BaseLazyFragment {
    private MovieSort.SortData sortData = null;
    private MovieSort.SortData activeSortData = null;
    private TvRecyclerView mGridView;
    private TvRecyclerView mSubCategoryView;
    private LinearLayout subCategoryBar;
    private TextView categoryState;
    private SubCategoryAdapter subCategoryAdapter;
    private SourceViewModel sourceViewModel;
    private GridFilterDialog gridFilterDialog;
    private GridAdapter gridAdapter;
    private int page = 1;
    private int maxPage = 1;
    private boolean isLoad = false;
    private boolean isTop = true;
    private View focusedView = null;

    private static class GridInfo{
        public String sortID="";
        public TvRecyclerView mGridView;
        public GridAdapter gridAdapter;
        public int page = 1;
        public int maxPage = 1;
        public boolean isLoad = false;
        public View focusedView = null;
    }

    private Runnable subCategoryRunnable;

    Stack<GridInfo> mGrids = new Stack<GridInfo>(); //ui栈

    public static GridFragment newInstance(MovieSort.SortData sortData) {
        GridFragment fragment = new GridFragment();
        Bundle args = new Bundle();
        if (sortData != null) {
            args.putString("sortDataJson", GsonUtils.toJson(sortData));
        }
        fragment.setArguments(args);
        fragment.sortData = sortData;
        return fragment;
    }

    public GridFragment setArguments(MovieSort.SortData sortData) {
        this.sortData = sortData;
        Bundle args = getArguments();
        if (args == null) {
            args = new Bundle();
            if (sortData != null) {
                args.putString("sortDataJson", GsonUtils.toJson(sortData));
            }
            super.setArguments(args);
        } else {
            if (sortData != null) {
                args.putString("sortDataJson", GsonUtils.toJson(sortData));
            } else {
                args.remove("sortDataJson");
            }
        }
        return this;
    }

    @Override
    protected int getLayoutResID() {
        return R.layout.fragment_grid;
    }
    
    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (this.sortData == null) {
            Bundle args = getArguments();
            if (args != null && args.containsKey("sortDataJson")) {
                this.sortData = GsonUtils.fromJson(args.getString("sortDataJson"), MovieSort.SortData.class);
            } else if (savedInstanceState != null && savedInstanceState.containsKey("sortDataJson")) {
                this.sortData = GsonUtils.fromJson(savedInstanceState.getString("sortDataJson"), MovieSort.SortData.class);
            }
        }
    }

    /** 焦点进出子分类栏 / 顶栏时没有滚动事件，靠全局焦点变化重新同步顶栏收起状态 */
    private final android.view.ViewTreeObserver.OnGlobalFocusChangeListener topBarFocusSync =
            (oldFocus, newFocus) -> syncTopBar();

    @Override
    protected void init() {
        if (rootView != null) {
            rootView.getViewTreeObserver().addOnGlobalFocusChangeListener(topBarFocusSync);
        }
        initSubCategories();
        initView();
        initViewModel();
        initData();
    }

    private void initSubCategories() {
        subCategoryBar = findViewById(R.id.subCategoryBar);
        mSubCategoryView = findViewById(R.id.mSubCategoryView);
        categoryState = findViewById(R.id.categoryState);
        categoryState.setOnClickListener(v -> forceRefresh());
        categoryState.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() == android.view.KeyEvent.ACTION_DOWN) {
                if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP || keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT) {
                    if (getActivity() instanceof HomeActivity) {
                        if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP && subCategoryBar != null
                                && subCategoryBar.getVisibility() == View.VISIBLE && mSubCategoryView != null) {
                            int sel = getSelectedSubCategoryPosition();
                            if (sel >= 0) {
                                mSubCategoryView.setSelectedPosition(sel);
                                mSubCategoryView.setSelection(sel);
                            }
                            mSubCategoryView.requestFocus();
                        } else {
                            ((HomeActivity) getActivity()).exitPosterBrowseAndFocusCategory();
                        }
                        return true;
                    }
                }
            }
            return false;
        });

        if (sortData == null || sortData.children == null || sortData.children.isEmpty()) {
            activeSortData = sortData;
            subCategoryBar.setVisibility(View.GONE);
            return;
        }

        activeSortData = CategoryOrganizer.defaultChild(sortData);
        boolean isMovie = sortData != null && (CategoryOrganizer.GROUP_MOVIE.equals(sortData.id)
                || (sortData.children != null && !sortData.children.isEmpty()
                    && CategoryOrganizer.MOVIE_RECOMMENDATION.equals(sortData.children.get(0).id)));
        if (!isMovie) {
            String savedId = Hawk.get(subCategoryPreferenceKey(), "");
            if (savedId != null && !savedId.isEmpty()) {
                for (MovieSort.SortData child : sortData.children) {
                    if (savedId.equals(child.id)) {
                        activeSortData = child;
                        break;
                    }
                }
            }
        }

        subCategoryBar.setVisibility(View.VISIBLE);
        subCategoryAdapter = new SubCategoryAdapter();
        subCategoryAdapter.setNewData(sortData.children);
        subCategoryAdapter.setSelectedId(activeSortData == null ? null : activeSortData.id);
        mSubCategoryView.setLayoutManager(new V7LinearLayoutManager(mContext, 0, false));
        mSubCategoryView.setAdapter(subCategoryAdapter);
        mSubCategoryView.setOnItemListener(new TvRecyclerView.OnItemListener() {
            @Override public void onItemPreSelected(TvRecyclerView parent, View itemView, int position) {
                if (itemView != null) {
                    Motion.unfocus(itemView);
                }
            }
            @Override public void onItemSelected(TvRecyclerView parent, View itemView, int position) {
                if (itemView != null) {
                    Motion.focusCard(itemView);
                }
                if (getActivity() instanceof HomeActivity) {
                    ((HomeActivity) getActivity()).setPosterBrowseMode(false);
                }
                mSubCategoryView.removeCallbacks(subCategoryRunnable);
                subCategoryRunnable = () -> {
                    if (subCategoryAdapter != null && position >= 0 && position < subCategoryAdapter.getData().size()) {
                        MovieSort.SortData selected = subCategoryAdapter.getItem(position);
                        selectSubCategory(selected);
                    }
                };
                mSubCategoryView.postDelayed(subCategoryRunnable, 250);
            }
            @Override public void onItemClick(TvRecyclerView parent, View itemView, int position) {
                if (subCategoryAdapter != null && position >= 0 && position < subCategoryAdapter.getData().size()) {
                    mSubCategoryView.removeCallbacks(subCategoryRunnable);
                    MovieSort.SortData selected = subCategoryAdapter.getItem(position);
                    selectSubCategory(selected);
                }
            }
        });
        mSubCategoryView.setOnInBorderKeyEventListener(new TvRecyclerView.OnInBorderKeyEventListener() {
            @Override
            public boolean onInBorderKeyEvent(int direction, View focused) {
                // 向左进入首页左侧导航栏（首页二次重构）
                if (direction == View.FOCUS_LEFT && getActivity() instanceof HomeActivity
                        && ((HomeActivity) getActivity()).focusNavRail()) {
                    return true;
                }
                if (direction == View.FOCUS_UP || direction == View.FOCUS_LEFT) {
                    if (getActivity() instanceof HomeActivity) {
                        ((HomeActivity) getActivity()).exitPosterBrowseAndFocusCategory();
                        return true;
                    }
                } else if (direction == View.FOCUS_DOWN) {
                    if (categoryState != null && categoryState.getVisibility() == View.VISIBLE) {
                        categoryState.requestFocus();
                        return true;
                    }
                    if (mGridView != null) {
                        mGridView.requestFocus();
                        if (getActivity() instanceof HomeActivity) {
                            ((HomeActivity) getActivity()).setPosterBrowseMode(true);
                        }
                        return true;
                    }
                }
                return false;
            }
        });
        mSubCategoryView.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) {
                int childCount = mSubCategoryView.getChildCount();
                for (int i = 0; i < childCount; i++) {
                    View child = mSubCategoryView.getChildAt(i);
                    if (child != null) {
                        Motion.unfocus(child);
                    }
                }
            }
        });
        subCategoryAdapter.setOnItemClickListener((adapter, view, position) -> {
            if (getActivity() instanceof HomeActivity) {
                ((HomeActivity) getActivity()).setPosterBrowseMode(false);
            }
            mSubCategoryView.removeCallbacks(subCategoryRunnable);
            MovieSort.SortData selected = subCategoryAdapter.getItem(position);
            selectSubCategory(selected);
        });
    }

    private void selectSubCategory(MovieSort.SortData selected) {
        if (selected == null || selected == activeSortData
                || (activeSortData != null && selected.id != null && selected.id.equals(activeSortData.id))) return;
        if (gridFilterDialog != null) {
            gridFilterDialog.dismiss();
            gridFilterDialog = null;
        }
        activeSortData = selected;
        if (selected.filterSelect != null) selected.filterSelect.clear();
        Hawk.put(subCategoryPreferenceKey(), selected.id);
        subCategoryAdapter.setSelectedId(selected.id);
        page = 1;
        maxPage = 1;
        isLoad = false;
        if (gridAdapter != null) {
            gridAdapter.setNewData(new ArrayList<>());
            gridAdapter.setEnableLoadMore(false);
        }
        initData();
    }

    public boolean focusSubCategoryOrGrid() {
        if (subCategoryBar != null && subCategoryBar.getVisibility() == View.VISIBLE) {
            if (mSubCategoryView != null) {
                int sel = getSelectedSubCategoryPosition();
                if (sel >= 0) {
                    mSubCategoryView.setSelectedPosition(sel);
                    mSubCategoryView.setSelection(sel);
                }
                return mSubCategoryView.requestFocus();
            }
        }
        if (categoryState != null && categoryState.getVisibility() == View.VISIBLE) {
            return categoryState.requestFocus();
        }
        if (mGridView != null) {
            return mGridView.requestFocus();
        }
        return false;
    }

    public int getSelectedSubCategoryPosition() {
        if (subCategoryAdapter == null || activeSortData == null) return 0;
        List<MovieSort.SortData> items = subCategoryAdapter.getData();
        for (int i = 0; i < items.size(); i++) {
            if (activeSortData.id != null && activeSortData.id.equals(items.get(i).id)) {
                return i;
            }
        }
        return 0;
    }

    private String subCategoryPreferenceKey() {
        SourceBean source = ApiConfig.get().getHomeSourceBean();
        String sourceIdentity = ApiConfig.get().getConfigRevision() + "#"
                + Hawk.get(HawkConfig.API_URL, "") + "#"
                + (source == null ? "" : source.getKey()) + "#"
                + (source == null ? "" : source.getApi());
        return "category_child_" + MD5.encode(sourceIdentity) + "#"
                + (sortData == null ? "" : sortData.id);
    }

    private MovieSort.SortData currentSortData() {
        return activeSortData == null ? sortData : activeSortData;
    }

    public void setPosterBrowseMode(boolean immersive) {
        if (subCategoryBar == null) return;
        boolean hasCategories = sortData != null && sortData.children != null && !sortData.children.isEmpty();
        // 不随海报浏览隐藏：隐藏会让网格瞬间上移造成跳帧（分类页顶栏和子分类栏常驻）
        subCategoryBar.setVisibility(mGrids.empty() && hasCategories ? View.VISIBLE : View.GONE);
    }

    /**
     * 与首页一致：网格往下滑时，顶栏和子分类栏随滚动距离一起平移淡出，回到顶部时再出现。
     * 顶部区域（顶栏让位 + 子分类栏）整体用 translationY 上移，不改 visibility，避免跳帧；
     * 网格容器底部用等高的负 margin 往下延伸，上移后底部不留空。
     */
    public void syncTopBar() {
        if (!isAdded() || mGridView == null || subCategoryBar == null || rootView == null
                || !(getActivity() instanceof HomeActivity)) {
            return;
        }
        HomeActivity home = (HomeActivity) getActivity();
        if (!home.isCurrentPage(this)) {
            return;
        }
        View holder = (View) mGridView.getParent();
        int range = rootView.getPaddingTop()
                + (subCategoryBar.getVisibility() == View.VISIBLE ? subCategoryBar.getHeight() : 0);
        ViewGroup.LayoutParams lp = holder.getLayoutParams();
        if (lp instanceof ViewGroup.MarginLayoutParams && ((ViewGroup.MarginLayoutParams) lp).bottomMargin != -range) {
            ((ViewGroup.MarginLayoutParams) lp).bottomMargin = -range;
            holder.setLayoutParams(lp);
        }
        // 容器往下延伸了 range，网格底部同步多留 range，保证最后一行在收起前也能完整滚进屏幕
        int bottom = getResources().getDimensionPixelSize(R.dimen.vs_40) + range;
        if (mGridView.getPaddingBottom() != bottom) {
            mGridView.setPadding(mGridView.getPaddingLeft(), mGridView.getPaddingTop(), mGridView.getPaddingRight(), bottom);
        }
        float progress = 0f;
        // 焦点在子分类栏上时保持展开，否则遥控器会落到看不见的子分类上
        if (range > 0 && !subCategoryBar.hasFocus()) {
            progress = Math.min(1f, mGridView.computeVerticalScrollOffset() / (float) range);
        }
        float applied = home.setTopBarProgress(progress);
        float shift = -applied * range;
        subCategoryBar.setTranslationY(shift);
        holder.setTranslationY(shift);
    }

    public boolean hasFilters() {
        MovieSort.SortData current = currentSortData();
        return current != null && current.filters != null && !current.filters.isEmpty();
    }

    public int selectedFilterCount() {
        MovieSort.SortData current = currentSortData();
        return current == null ? 0 : current.filterSelectCount();
    }
    
    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString("sortDataJson", GsonUtils.toJson(sortData));        
    }

    private void changeView(String id,Boolean isFolder){
        MovieSort.SortData current = currentSortData();
        if(isFolder){
            current.flag =style==null?"1":"2"; // 修改当前真实分类的显示模式
        }else {
            current.flag ="2";
        }
        initView();
        current.id = id;
        if (subCategoryBar != null) subCategoryBar.setVisibility(View.GONE);
        initViewModel();
        initData();
    }

    public boolean isFolederMode() {
        return (getUITag() == '1');
    }

    // 获取当前页面UI的显示模式 ‘0’ 正常模式 '1' 文件夹模式 '2' 显示缩略图的文件夹模式
    public char getUITag() {
        MovieSort.SortData current = currentSortData();
        return (current == null || current.flag == null || current.flag.length() == 0) ? '0' : current.flag.charAt(0);
    }

    // 是否允许聚合搜索 sortData.flag的第二个字符为‘1’时允许聚搜
    public boolean enableFastSearch() {
        MovieSort.SortData current = currentSortData();
        return current == null || current.flag == null || current.flag.length() < 2 || (current.flag.charAt(1) == '1');
    }
    //public boolean enableFastSearch() {  return (sortData.flag == null || sortData.flag.length() < 2) ? true : (sortData.flag.charAt(1) == '1'); }

    // 保存当前页面
    private void saveCurrentView() {
        if (this.mGridView == null) return;
        GridInfo info = new GridInfo();
        info.sortID = currentSortData().id;
        info.mGridView = this.mGridView;
        info.gridAdapter = this.gridAdapter;
        info.page = this.page;
        info.maxPage = this.maxPage;
        info.isLoad = this.isLoad;
        info.focusedView = this.focusedView;
        this.mGrids.push(info);
    }

    // 丢弃当前页面，将页面还原成上一个保存的页面
    public boolean restoreView() {
        if (mGrids.empty()) return false;
        if (sourceViewModel != null) sourceViewModel.cancelActiveListRequest();
        this.showSuccess();
        ((ViewGroup) mGridView.getParent()).removeView(this.mGridView); // 重父窗口移除当前控件
        GridInfo info = mGrids.pop();// 还原上次保存的控件
        currentSortData().id = info.sortID;
        this.mGridView = info.mGridView;
        this.gridAdapter = info.gridAdapter;
        this.page = info.page;
        this.maxPage = info.maxPage;
        this.isLoad = info.isLoad;
        this.focusedView = info.focusedView;
        this.mGridView.setVisibility(View.VISIBLE);
        if (subCategoryBar != null && mGrids.empty()
                && sortData != null && sortData.children != null && !sortData.children.isEmpty()) {
            boolean browseMode = getActivity() instanceof HomeActivity && ((HomeActivity) getActivity()).isPosterBrowseMode();
            subCategoryBar.setVisibility(browseMode ? View.GONE : View.VISIBLE);
        }
//        if(this.focusedView != null){ this.focusedView.requestFocus(); }
        if (mGridView != null) mGridView.requestFocus();
        return true;
    }

    private ImgUtil.Style style;
    // 更改当前页面
    private void createView() {
        this.saveCurrentView(); // 保存当前页面
        if (mGridView == null) { // 从layout中拿view
            mGridView = findViewById(R.id.mGridView);
        } else { // 复制当前view
            TvRecyclerView v3 = new TvRecyclerView(this.mContext);
            v3.setSpacingWithMargins(10, 10);
            v3.setLayoutParams(mGridView.getLayoutParams());
            v3.setPadding(mGridView.getPaddingLeft(), mGridView.getPaddingTop(), mGridView.getPaddingRight(), mGridView.getPaddingBottom());
            v3.setClipToPadding(mGridView.getClipToPadding());
            ((ViewGroup) mGridView.getParent()).addView(v3);
            mGridView.setVisibility(View.GONE);
            mGridView = v3;
            mGridView.setVisibility(View.VISIBLE);
        }
        mGridView.setHasFixedSize(true);
        style=ImgUtil.initStyle();
        gridAdapter = new GridAdapter(isFolederMode(), style);
        this.page = 1;
        this.maxPage = 1;
        this.isLoad = false;
    }

    private void initView() {
        this.createView();
        mGridView.setAdapter(gridAdapter);
        if (isFolederMode()) {
            mGridView.setLayoutManager(new V7LinearLayoutManager(this.mContext, 1, false));
        } else {
            int spanCount = isBaseOnWidth() ? 4 : 5;
            if (style != null) {
                spanCount = ImgUtil.spanCountByStyle(style, spanCount);
            }
            if (spanCount == 1) {
                mGridView.setLayoutManager(new V7LinearLayoutManager(mContext, spanCount, false));
            } else {
                mGridView.setLayoutManager(new V7GridLayoutManager(mContext, spanCount));
            }
        }

        gridAdapter.setOnLoadMoreListener(new BaseQuickAdapter.RequestLoadMoreListener() {
            @Override
            public void onLoadMoreRequested() {
                gridAdapter.setEnableLoadMore(true);
                sourceViewModel.getList(currentSortData(), page);
            }
        }, mGridView);
        mGridView.setOnItemListener(new TvRecyclerView.OnItemListener() {
            @Override
            public void onItemPreSelected(TvRecyclerView parent, View itemView, int position) {
                if (itemView != null) {
                    Motion.unfocus(itemView);
                }
            }

            @Override
            public void onItemSelected(TvRecyclerView parent, View itemView, int position) {
                if (itemView != null) {
                    Motion.focusCard(itemView);
                }
                if (getActivity() instanceof HomeActivity) {
                    HomeActivity home = (HomeActivity) getActivity();
                    if (parent != null && (parent.hasFocus() || parent.isFocused())) {
                        home.setPosterBrowseMode(true);
                    }
                    Movie.Video vod = gridAdapter == null ? null : gridAdapter.getItem(position);
                    if (vod != null) {
                        home.showAmbient(vod.pic);
                    }
                }
            }

            @Override
            public void onItemClick(TvRecyclerView parent, View itemView, int position) {

            }
        });
        mGridView.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus && getActivity() instanceof HomeActivity) {
                ((HomeActivity) getActivity()).setPosterBrowseMode(true);
            }
        });
        mGridView.addOnScrollListener(new RecyclerView.OnScrollListener() {
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
                if (recyclerView == mGridView) {
                    syncTopBar();
                }
                if (getActivity() instanceof HomeActivity) {
                    if (dy > 15) {
                        ((HomeActivity) getActivity()).setPosterBrowseMode(true);
                    } else if (dy < -15 && !recyclerView.canScrollVertically(-1)) {
                        ((HomeActivity) getActivity()).setPosterBrowseMode(false);
                    }
                }
            }
        });
        mGridView.setOnInBorderKeyEventListener(new TvRecyclerView.OnInBorderKeyEventListener() {
            @Override
            public boolean onInBorderKeyEvent(int direction, View focused) {
                if (direction == View.FOCUS_LEFT && getActivity() instanceof HomeActivity
                        && ((HomeActivity) getActivity()).focusNavRail()) {
                    return true;
                }
                if (getActivity() instanceof HomeActivity
                        && (direction == View.FOCUS_LEFT || direction == View.FOCUS_UP)) {
                    HomeActivity home = (HomeActivity) getActivity();
                    if (direction == View.FOCUS_UP && sortData != null && sortData.children != null
                            && !sortData.children.isEmpty() && mGrids.empty()) {
                        home.setPosterBrowseMode(false);
                        if (mSubCategoryView != null) {
                            mSubCategoryView.post(() -> {
                                int sel = getSelectedSubCategoryPosition();
                                if (sel >= 0) {
                                    mSubCategoryView.setSelectedPosition(sel);
                                    mSubCategoryView.setSelection(sel);
                                }
                                mSubCategoryView.requestFocus();
                            });
                        } else {
                            home.exitPosterBrowseAndFocusCategory();
                        }
                    } else {
                        home.exitPosterBrowseAndFocusCategory();
                    }
                    return true;
                }
                return false;
            }
        });
        gridAdapter.setOnItemClickListener(new BaseQuickAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(BaseQuickAdapter adapter, View view, int position) {
                FastClickCheckUtil.check(view);
                Movie.Video video = gridAdapter.getData().get(position);
                if (video != null) {
                    Bundle bundle = new Bundle();
                    bundle.putString("id", video.id);
                    bundle.putString("sourceKey", video.sourceKey);
                    bundle.putString("title", video.name);
                    if( video.tag !=null && (video.tag.equals("folder") || video.tag.equals("cover"))){
                        focusedView = view;
                        if(("12".indexOf(getUITag()) != -1)){
                            changeView(video.id,video.tag.equals("folder"));
                        }else {
                            changeView(video.id,false);
                        }
                    } else {
                        // 子分类内容作为聚合/展示，点击效果与首页一致进入多源聚合搜索结果
                        if (video.name != null && !video.name.trim().isEmpty()) {
                            jumpActivity(FastSearchActivity.class, bundle);
                        } else if (video.id != null && !video.id.isEmpty() && !video.id.startsWith("msearch:")) {
                            jumpActivity(DetailActivity.class, bundle);
                        } else {
                            jumpActivity(SearchActivity.class, bundle);
                        }
                    }

                }
            }
        });
        // 长按保留直接进入当前源详情页的能力
        gridAdapter.setOnItemLongClickListener(new BaseQuickAdapter.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(BaseQuickAdapter adapter, View view, int position) {
                FastClickCheckUtil.check(view);
                Movie.Video video = gridAdapter.getData().get(position);
                if (video != null) {
                    Bundle bundle = new Bundle();
                    bundle.putString("id", video.id);
                    bundle.putString("sourceKey", video.sourceKey);
                    bundle.putString("title", video.name);
                    if (video.id != null && !video.id.isEmpty() && !video.id.startsWith("msearch:")) {
                        jumpActivity(DetailActivity.class, bundle);
                    } else {
                        jumpActivity(FastSearchActivity.class, bundle);
                    }
                }
                return true;
            }
        });
        gridAdapter.setLoadMoreView(new LoadMoreView());
        setLoadSir(mGridView);
    }

    private void initViewModel() {
        if (sourceViewModel != null) {
            return;
        }
        sourceViewModel = new ViewModelProvider(this).get(SourceViewModel.class);
        sourceViewModel.listResult.observe(this, new Observer<AbsXml>() {
            @Override
            public void onChanged(AbsXml absXml) {
//                if(mGridView != null) mGridView.requestFocus();
                if (absXml != null && absXml.movie != null && absXml.movie.videoList != null && absXml.movie.videoList.size() > 0) {
                    if (page == 1) {
                        showSuccess();
                        hideCategoryState();
                        isLoad = true;
                        gridAdapter.setNewData(absXml.movie.videoList);
                    } else {
                        gridAdapter.addData(absXml.movie.videoList);
                    }
                    page++;
                    maxPage = absXml.movie.pagecount;
                    if (page > maxPage && maxPage!=0) {
                        gridAdapter.loadMoreEnd();
                        gridAdapter.setEnableLoadMore(false);
                    } else {
                        gridAdapter.loadMoreComplete();
                        gridAdapter.setEnableLoadMore(true);
                    }
                } else {
                    if (page == 1) {
                        showSuccess();
                        showCategoryState("暂无内容，按确定重试");
                    }
                    if (page > maxPage && maxPage!=0) {
                        Toast.makeText(getContext(), "没有更多了", Toast.LENGTH_SHORT).show();
                        gridAdapter.loadMoreEnd();
                    } else {
                        gridAdapter.loadMoreComplete();
                    }
                    gridAdapter.setEnableLoadMore(false);
                }
            }
        });
        sourceViewModel.listError.observe(this, error -> {
            if (error == null) return;
            String message = error == 2
                    ? "请求超时，按确定重试"
                    : "加载失败，按确定重试";
            if (page == 1) {
                showSuccess();
                showCategoryState(message);
            } else {
                Toast.makeText(getContext(), message, Toast.LENGTH_SHORT).show();
                gridAdapter.loadMoreFail();
            }
        });
    }

    public boolean isLoad() {
        return isLoad
                || (categoryState != null && categoryState.getVisibility() == View.VISIBLE)
                || !mGrids.empty(); //如果有缓存页或可重试状态，也可以向下移动焦点
    }

    private void initData() {
        SourceBean source = ApiConfig.get().getHomeSourceBean();
        if (source == null || source.getApi() == null) {
            showSuccess();
            showCategoryState("来源不可用，按确定重试");
            return;
        }
        hideCategoryState();
        // 海报网格用骨架屏占位；列表（文件夹）模式仍用转圈
        if (mGridView.getLayoutManager() instanceof V7GridLayoutManager) {
            showPosterSkeleton();
        } else {
            showLoading();
        }
        isLoad = false;
        scrollTop();
        toggleFilterStatus();
        sourceViewModel.getList(currentSortData(), page);
    }

    private void showCategoryState(String message) {
        if (categoryState == null) return;
        if (getActivity() instanceof HomeActivity) ((HomeActivity) getActivity()).setPosterBrowseMode(false);
        categoryState.setText(message);
        categoryState.setVisibility(View.VISIBLE);
        categoryState.requestFocus();
    }

    private void hideCategoryState() {
        if (categoryState != null) categoryState.setVisibility(View.GONE);
    }

    private void toggleFilterStatus() {
        MovieSort.SortData current = currentSortData();
        int count = current == null ? 0 : current.filterSelectCount();
        EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_FILTER_CHANGE, count));
    }

    public boolean isTop() {
        return isTop;
    }

    public void scrollTop() {
        isTop = true;
        mGridView.scrollToPosition(0);
    }

    public void showFilter() {
        MovieSort.SortData current = currentSortData();
        if (current != null && !current.filters.isEmpty() && gridFilterDialog == null) {
            gridFilterDialog = new GridFilterDialog(mContext);
//            gridFilterDialog.setData(current);
//            gridFilterDialog.setOnDismiss(new GridFilterDialog.Callback() {
//                @Override
//                public void change() {
//                    page = 1;
//                    initData();
//                }
//            });
            setFilterDialogData();
        }
        if (gridFilterDialog != null)
            gridFilterDialog.show();
    }

    public void setFilterDialogData() {
        MovieSort.SortData current = currentSortData();
        if (current == null) return;
        Context context = getContext();
        LayoutInflater inflater = LayoutInflater.from(context);
        assert context != null;

        // 获取动态主题颜色
        TypedArray a = getContext().obtainStyledAttributes(R.styleable.themeColor);
        int selectedColor = a.getColor(R.styleable.themeColor_color_theme, 0); // 选择的颜色
        int defaultColor = ContextCompat.getColor(context, R.color.color_FFFFFF);
        // 释放 TypedArray 资源
        a.recycle();
        // 遍历过滤条件数据
        for (MovieSort.SortFilter filter : current.filters) {
            View line = inflater.inflate(R.layout.item_grid_filter, gridFilterDialog.filterRoot, false);
            TextView filterNameTv = line.findViewById(R.id.filterName);
            filterNameTv.setText(filter.name);
            TvRecyclerView gridView = line.findViewById(R.id.mFilterKv);
            gridView.setHasFixedSize(true);
            gridView.setLayoutManager(new V7LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false));
            GridFilterKVAdapter adapter = new GridFilterKVAdapter();
            gridView.setAdapter(adapter);
            final String key = filter.key;
            final ArrayList<String> values = new ArrayList<>(filter.values.keySet());
            final ArrayList<String> keys = new ArrayList<>(filter.values.values());
            adapter.setOnItemClickListener(new BaseQuickAdapter.OnItemClickListener() {
                // 用于记录上一次选中的 view
                View previousSelectedView = null;
                @Override
                public void onItemClick(BaseQuickAdapter adapter, View view, int position) {
                    String currentSelection = current.filterSelect.get(key);
                    String newSelection = keys.get(position);
                    if (currentSelection == null || !currentSelection.equals(newSelection)) {
                        // 更新选中状态
                        current.filterSelect.put(key, newSelection);
                        updateViewStyle(view, selectedColor, true);
                        if (previousSelectedView != null) {
                            updateViewStyle(previousSelectedView, defaultColor, false);
                        }
                        previousSelectedView = view;
                    } else {
                        // 取消选中
                        current.filterSelect.remove(key);
                        if (previousSelectedView != null) {
                            updateViewStyle(previousSelectedView, defaultColor, false);
                        }
                        previousSelectedView = null;
                    }
                    forceRefresh();
                }
                private void updateViewStyle(View view, int color, boolean isBold) {
                    TextView valueTv = view.findViewById(R.id.filterValue);
                    valueTv.getPaint().setFakeBoldText(isBold);
                    valueTv.setTextColor(color);
                }
            });
            adapter.setNewData(values);
            gridFilterDialog.filterRoot.addView(line);
        }
    }

    public void forceRefresh() {
        page = 1;
        maxPage = 1;
        isLoad = false;
        if (gridAdapter != null) {
            gridAdapter.setNewData(new ArrayList<>());
            gridAdapter.setEnableLoadMore(false);
        }
        initData();
    }

    @Override
    public void onDestroyView() {
        if (rootView != null) {
            rootView.getViewTreeObserver().removeOnGlobalFocusChangeListener(topBarFocusSync);
        }
        if (mSubCategoryView != null && subCategoryRunnable != null) {
            mSubCategoryView.removeCallbacks(subCategoryRunnable);
        }
        super.onDestroyView();
    }
}
