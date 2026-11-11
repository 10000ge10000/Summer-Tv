package com.github.tvbox.osc.ui.activity;

import android.Manifest;
import android.content.Context;
import android.animation.Animator;
import android.animation.AnimatorSet;
import android.animation.IntEvaluator;
import android.animation.ObjectAnimator;
import android.annotation.SuppressLint;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.res.Resources;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager.widget.ViewPager;

import com.github.tvbox.osc.ui.tv.AmbientBackdrop;
import com.github.tvbox.osc.ui.tv.PosterFocusController;
import com.github.tvbox.osc.ui.tv.widget.FocusRingView;
import com.github.tvbox.osc.ui.tv.Motion;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.base.App;
import com.github.tvbox.osc.base.BaseActivity;
import com.github.tvbox.osc.base.BaseLazyFragment;
import com.github.tvbox.osc.bean.AbsSortXml;
import com.github.tvbox.osc.bean.MovieSort;
import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.commercial.AdPlacement;
import com.github.tvbox.osc.commercial.MonetizationManager;
import com.github.tvbox.osc.event.RefreshEvent;
import com.github.tvbox.osc.server.ControlManager;
import com.github.tvbox.osc.ui.adapter.HomePageAdapter;
import com.github.tvbox.osc.ui.adapter.SelectDialogAdapter;
import com.github.tvbox.osc.ui.adapter.SortAdapter;
import com.github.tvbox.osc.ui.dialog.SelectDialog;
import com.github.tvbox.osc.ui.dialog.TipDialog;
import com.github.tvbox.osc.update.UpdateManager;
import com.github.tvbox.osc.ui.fragment.GridFragment;
import com.github.tvbox.osc.ui.fragment.UserFragment;
import com.github.tvbox.osc.ui.tv.widget.DefaultTransformer;
import com.github.tvbox.osc.util.CategoryOrganizer;
import com.github.tvbox.osc.ui.tv.widget.FixedSpeedScroller;
import com.github.tvbox.osc.ui.tv.widget.NoScrollViewPager;
import com.github.tvbox.osc.ui.tv.widget.ViewObj;
import com.github.tvbox.osc.util.AppManager;
import com.github.tvbox.osc.util.DefaultConfig;
import com.github.tvbox.osc.util.FastClickCheckUtil;
import com.github.tvbox.osc.util.FileUtils;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.LOG;
import com.github.tvbox.osc.util.MD5;
import com.github.tvbox.osc.viewmodel.SourceViewModel;
import com.orhanobut.hawk.Hawk;
import com.owen.tvrecyclerview.widget.TvRecyclerView;
import com.owen.tvrecyclerview.widget.V7GridLayoutManager;
import com.owen.tvrecyclerview.widget.V7LinearLayoutManager;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.lang.reflect.Field;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Objects;

import me.jessyan.autosize.utils.AutoSizeUtils;

public class HomeActivity extends BaseActivity {

    // takagen99: Added to allow read string
    private static Resources res;

    private View currentView;
    private LinearLayout topLayout;
    private LinearLayout contentLayout;
    private TextView tvName;
    // 左侧导航栏与右上角设置（首页二次重构）
    private View[] navRailItems;
    private View topScrim;
    /** 顶栏当前收起程度：0 = 完全显示，1 = 完全收起 */
    private float topBarProgress = 0f;
    /** 进入导航栏前内容区里的焦点，按右键时回到这里，不打断海报浏览位置 */
    private View lastContentFocus;
    private ImageView tvWifi;
    private ImageView tvFind;
    private ImageView tvStyle;
    private ImageView tvDraw;
    private ImageView tvMenu;
    private TextView tvDate;
    private TvRecyclerView mGridView;
    private NoScrollViewPager mViewPager;
    private SourceViewModel sourceViewModel;
    private SortAdapter sortAdapter;
    private HomePageAdapter pageAdapter;
    private final List<BaseLazyFragment> fragments = new ArrayList<>();
    private boolean isDownOrUp = false;
    private boolean sortChange = false;
    private int currentSelected = 0;
    private int sortFocused = 0;
    public View sortFocusView = null;
    private boolean posterBrowseMode = false;
    private AmbientBackdrop ambientBackdrop;
    private PosterFocusController posterFocus;
    private final Handler mHandler = new Handler();
    private long mExitTime = 0;
    private final Runnable mRunnable = new Runnable() {
        @SuppressLint({"DefaultLocale", "SetTextI18n"})
        @Override
        public void run() {
            if (tvDate == null || tvDate.getVisibility() != View.VISIBLE) return;
            Date date = new Date();
            @SuppressLint("SimpleDateFormat")
			//修改时间分隔符
            SimpleDateFormat timeFormat = new SimpleDateFormat(getString(R.string.hm_date1) + " | " + getString(R.string.hm_date2));
            tvDate.setText(timeFormat.format(date));
            mHandler.postDelayed(this, 1000);
        }
    };

    @Override
    protected int getLayoutResID() {
        return R.layout.activity_home;
    }

    boolean useCacheConfig = false;

    @Override
    protected void init() {
        // takagen99: Added to allow read string
        res = getResources();
        startSplash();

        EventBus.getDefault().register(this);
        // 本地代理服务只监听 127.0.0.1，供片源代理 / m3u8 去广告等内部使用，局域网设备连不进来
        ControlManager.get().startServer();
        initView();
        initViewModel();
        useCacheConfig = false;
        Intent intent = getIntent();
        if (intent != null && intent.getExtras() != null) {
            Bundle bundle = intent.getExtras();
            useCacheConfig = bundle.getBoolean("useCache", false);
        }
        initData();
    }

    // takagen99: Added to allow read string
    public static Resources getRes() {
        return res;
    }

    private void initView() {
        this.topLayout = findViewById(R.id.topLayout);
        this.topScrim = findViewById(R.id.topScrim);
        this.tvName = findViewById(R.id.tvName);
        this.tvWifi = findViewById(R.id.tvWifi);
        this.tvFind = findViewById(R.id.tvFind);
        this.tvStyle = findViewById(R.id.tvStyle);
        this.tvDraw = findViewById(R.id.tvDrawer);
        this.tvMenu = findViewById(R.id.tvMenu);
        this.tvDate = findViewById(R.id.tvDate);
        this.contentLayout = findViewById(R.id.contentLayout);
        this.mGridView = findViewById(R.id.mGridViewCategory);
        this.mViewPager = findViewById(R.id.mViewPager);
        initNavRail();
        this.ambientBackdrop = new AmbientBackdrop(findViewById(R.id.ivAmbientA), findViewById(R.id.ivAmbientB), 0.9f);
        FocusRingView focusRing = findViewById(R.id.focusRing);
        focusRing.setColor(getThemeColor());
        this.posterFocus = new PosterFocusController(mViewPager, focusRing);
        this.posterFocus.attach();
        this.sortAdapter = new SortAdapter();
        this.mGridView.setLayoutManager(new V7LinearLayoutManager(this.mContext, 0, false));
        // 分类标签相对整屏居中：右侧补一块与左侧标志区（标志 + 字标）等宽的留白
        tvName.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            View balance = findViewById(R.id.topBarBalance);
            ViewGroup bar = (ViewGroup) v.getParent();
            ViewGroup.MarginLayoutParams mlp = (ViewGroup.MarginLayoutParams) v.getLayoutParams();
            int width = Math.max(0, r + mlp.rightMargin - bar.getPaddingRight());
            ViewGroup.LayoutParams lp = balance.getLayoutParams();
            if (lp.width != width) {
                lp.width = width;
                balance.post(() -> balance.setLayoutParams(lp));
            }
        });
        this.mGridView.setSpacingWithMargins(0, AutoSizeUtils.dp2px(this.mContext, 10.0f));
        this.mGridView.setAdapter(this.sortAdapter);
        sortAdapter.registerAdapterDataObserver(new RecyclerView.AdapterDataObserver() {
            @Override
            public void onChanged() {
                mGridView.post(() -> {
                    View firstChild = Objects.requireNonNull(mGridView.getLayoutManager()).findViewByPosition(0);
                    if (firstChild != null) {
                        mGridView.setSelectedPosition(0);
                        firstChild.requestFocus();
                    }
                });
            }
        });
        this.mGridView.setOnItemListener(new TvRecyclerView.OnItemListener() {
            public void onItemPreSelected(TvRecyclerView tvRecyclerView, View view, int position) {
                if (view != null && !HomeActivity.this.isDownOrUp) {
                    Motion.unfocus(view);
                    // 文字颜色由 st_tab_text 按 focused / activated 状态切换，这里只处理粗细
                    TextView textView = view.findViewById(R.id.tvTitle);
                    textView.getPaint().setFakeBoldText(false);
                    textView.invalidate();
                    view.findViewById(R.id.tvFilter).setVisibility(View.GONE);
                }
            }

            public void onItemSelected(TvRecyclerView tvRecyclerView, View view, int position) {
                if (view != null) {
                    HomeActivity.this.currentView = view;
                    HomeActivity.this.isDownOrUp = false;
                    HomeActivity.this.sortChange = true;
                    Motion.focus(view, Motion.SCALE_TAB);
                    TextView textView = view.findViewById(R.id.tvTitle);
                    textView.getPaint().setFakeBoldText(true);
                    textView.invalidate();
//                    if (!sortAdapter.getItem(position).filters.isEmpty())
//                        view.findViewById(R.id.tvFilter).setVisibility(View.VISIBLE);
                    if (position == -1) {
                        position = 0;
                        HomeActivity.this.mGridView.setSelection(0);
                    }
                    MovieSort.SortData sortData = sortAdapter.getItem(position);
                    if (null != sortData && position < fragments.size()
                            && fragments.get(position) instanceof GridFragment) {
                        GridFragment grid = (GridFragment) fragments.get(position);
                        if (grid.hasFilters()) showFilterIcon(grid.selectedFilterCount());
                    }
                    HomeActivity.this.sortFocusView = view;
                    HomeActivity.this.sortFocused = position;
                    mHandler.removeCallbacks(mDataRunnable);
                    mHandler.postDelayed(mDataRunnable, 200);
                }
            }

            @Override
            public void onItemClick(TvRecyclerView parent, View itemView, int position) {
                if (itemView != null && currentSelected == position) {
                    BaseLazyFragment baseLazyFragment = fragments.get(currentSelected);
                    if ((baseLazyFragment instanceof GridFragment)
                            && ((GridFragment) baseLazyFragment).hasFilters()) {// 弹出当前子分类筛选
                        ((GridFragment) baseLazyFragment).showFilter();
                    } else if (baseLazyFragment instanceof UserFragment) {
                        showSiteSwitch();
                    }
                }
            }
        });
        this.mGridView.setOnInBorderKeyEventListener(new TvRecyclerView.OnInBorderKeyEventListener() {
            public boolean onInBorderKeyEvent(int direction, View view) {
                if (direction == View.FOCUS_UP) {
                    BaseLazyFragment baseLazyFragment = fragments.get(sortFocused);
                    if ((baseLazyFragment instanceof GridFragment)) {
                        ((GridFragment) baseLazyFragment).forceRefresh();
                    }
                }
                if (direction == View.FOCUS_RIGHT) {
                    // 最后一个分类再往右没有可聚焦元素，停在原处
                    return true;
                }
                if (direction != View.FOCUS_DOWN) {
                    return false;
                }
                BaseLazyFragment baseLazyFragment = fragments.get(sortFocused);
                if (baseLazyFragment instanceof UserFragment) {
                    // 首页：向下先到快捷入口，不直接跳进海报（否则 header 会被海报浏览模式隐藏）
                    return ((UserFragment) baseLazyFragment).focusHomeHeader();
                }
                if (!(baseLazyFragment instanceof GridFragment)) {
                    return false;
                }
                GridFragment grid = (GridFragment) baseLazyFragment;
                if (!grid.isLoad()) {
                    return true;
                }
                return grid.focusSubCategoryOrGrid();
            }
        });
        // 点 Summer TV 标志 / 字标：打开关于页（第三轮：右上角关于角标移除后的入口）
        View.OnClickListener openAbout = v -> {
            FastClickCheckUtil.check(v);
            jumpActivity(AboutActivity.class);
        };
        tvName.setOnClickListener(openAbout);
        findViewById(R.id.stLogoMark).setOnClickListener(openAbout);
        // 长按字标：清理缓存并重新加载首页（原单击行为）
        tvName.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                File dir = getCacheDir();
                FileUtils.recursiveDelete(dir);
                dir = getExternalCacheDir();
                FileUtils.recursiveDelete(dir);
                Toast.makeText(HomeActivity.this, getString(R.string.hm_cache_del), Toast.LENGTH_SHORT).show();
                if(dataInitOk && jarInitOk){
                    String cspCachePath = FileUtils.getFilePath()+"/csp/";
                    String jar=ApiConfig.get().getHomeSourceBean().getJar();
                    String jarUrl=!jar.isEmpty()?jar:ApiConfig.get().getSpider();
                    File cspCacheDir = new File(cspCachePath + MD5.string2MD5(jarUrl)+".jar");
                    if (!cspCacheDir.exists()){
                        reloadHome();
                        return true;
                    }
                    new Thread(() -> {
                        try {
                            FileUtils.deleteFile(cspCacheDir);
                            ApiConfig.get().clearJarLoader();
                            reloadHome();
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }).start();
                } else {
                    reloadHome();
                }
                return true;
            }
        });
        // Button : Wifi >> Go into Android Wifi Settings -------------
        tvWifi.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                try {
                    startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS));
                }catch (Exception ignored){
                }
            }
        });
        // Button : Search --------------------------------------------
        tvFind.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                MonetizationManager.get().track("search_open", null);
                jumpActivity(SearchActivity.class);
            }
        });
        // Button : Style --------------------------------------------
        tvStyle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                try {
                    Hawk.put(HawkConfig.HOME_REC_STYLE, !Hawk.get(HawkConfig.HOME_REC_STYLE, false));
                    if (Hawk.get(HawkConfig.HOME_REC_STYLE, false)) {
                        UserFragment.tvHotListForGrid.setVisibility(View.VISIBLE);
                        UserFragment.tvHotListForLine.setVisibility(View.GONE);
                        Toast.makeText(HomeActivity.this, getString(R.string.hm_style_grid), Toast.LENGTH_SHORT).show();
                        tvStyle.setImageResource(R.drawable.hm_up_down);
                    } else {
                        UserFragment.tvHotListForGrid.setVisibility(View.GONE);
                        UserFragment.tvHotListForLine.setVisibility(View.VISIBLE);
                        Toast.makeText(HomeActivity.this, getString(R.string.hm_style_line), Toast.LENGTH_SHORT).show();
                        tvStyle.setImageResource(R.drawable.hm_left_right);
                    }
                } catch (Exception ex) {
                }
            }
        });
        // Button : Drawer >> To go into App Drawer -------------------
        tvDraw.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                jumpActivity(AppsActivity.class);
            }
        });
        // Button : Settings >> To go into Settings --------------------
        tvMenu.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                jumpActivity(AboutActivity.class);
            }
        });
        // Button : Settings >> To go into App Settings ----------------
        tvMenu.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View view) {
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", getPackageName(), null)));
                return true;
            }
        });
        // Button : Date >> Go into Android Date Settings --------------
        tvDate.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                startActivity(new Intent(Settings.ACTION_DATE_SETTINGS));
            }
        });
        setLoadSir(this.contentLayout);
        //mHandler.postDelayed(mFindFocus, 250);
    }
    //站点切换
    public static void homeRecf() {
        int homeRec = Hawk.get(HawkConfig.HOME_REC, -1);
        int limit = 2;
        if (homeRec == limit) homeRec = -1;
        homeRec++;
        Hawk.put(HawkConfig.HOME_REC, homeRec);
    }
    
    public static boolean reHome(Context appContext) {
        Intent intent = new Intent(appContext, HomeActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK);
        Bundle bundle = new Bundle();
        bundle.putBoolean("useCache", true);
        intent.putExtras(bundle);
        appContext.startActivity(intent);
        return true;
    }

    private boolean skipNextUpdate = false;	
    private void initViewModel() {
        sourceViewModel = new ViewModelProvider(this).get(SourceViewModel.class);
        sourceViewModel.sortResult.observe(this, new Observer<AbsSortXml>() {
            @Override
            public void onChanged(AbsSortXml absXml) {
                if (skipNextUpdate) {
                    skipNextUpdate = false;
                    return;
                }
                showSuccess();
                if (absXml != null && absXml.classes != null && absXml.classes.sortList != null) {
                    sortAdapter.setNewData(buildHomeCategories(absXml.classes.sortList));
                } else {
                    sortAdapter.setNewData(buildHomeCategories(new ArrayList<>()));
                }
                initViewPager(absXml);
                // takagen99 : Switch to show / hide source title
                SourceBean home = ApiConfig.get().getHomeSourceBean();
                tvName.clearAnimation();
                if (HomeShow && home != null && home.getName() != null && !home.getName().isEmpty()) {
                    tvName.setText(home.getName());
                } else {
                    tvName.setText(R.string.app_name);
                }
            }
        });
    }

    private List<MovieSort.SortData> buildHomeCategories(List<MovieSort.SortData> rawCategories) {
        SourceBean homeSource = ApiConfig.get().getHomeSourceBean();
        if (homeSource == null) {
            return DefaultConfig.adjustSort(null, rawCategories, true);
        }
        List<MovieSort.SortData> filtered = new ArrayList<>();
        List<String> configuredNames = homeSource.getCategories();
        for (MovieSort.SortData category : rawCategories) {
            if (category == null || (configuredNames != null && !configuredNames.isEmpty()
                    && !configuredNames.contains(category.name))) continue;
            if (category.filters == null) category.filters = new ArrayList<>();
            filtered.add(category);
        }
        List<MovieSort.SortData> grouped = new ArrayList<>(CategoryOrganizer.organize(filtered));
        if (!grouped.isEmpty()) {
            grouped.add(0, new MovieSort.SortData("my0", getString(R.string.app_home)));
            return grouped;
        }
        return DefaultConfig.adjustSort(homeSource.getKey(), rawCategories, true);
    }

    private boolean dataInitOk = false;
    private boolean jarInitOk = false;
    /** 无配置指引每个 Activity 生命周期只弹一次，避免重试时反复打断 */
    private boolean noConfigGuideShown = false;

    // takagen99 : Switch to show / hide source title
    boolean HomeShow = Hawk.get(HawkConfig.HOME_SHOW_SOURCE, false);

    // takagen99 : Check if network is available
    boolean isNetworkAvailable() {
        ConnectivityManager cm
                = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        NetworkInfo activeNetworkInfo = cm.getActiveNetworkInfo();
        return activeNetworkInfo != null && activeNetworkInfo.isConnectedOrConnecting();
    }

    private void initData() {
        // takagen99: If network available, check connected Wifi or Lan
        if (isNetworkAvailable()) {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            if (cm.getActiveNetworkInfo().getType() == ConnectivityManager.TYPE_WIFI) {
                tvWifi.setImageDrawable(res.getDrawable(R.drawable.hm_wifi));
            } else if (cm.getActiveNetworkInfo().getType() == ConnectivityManager.TYPE_MOBILE) {
                tvWifi.setImageDrawable(res.getDrawable(R.drawable.hm_mobile));
            } else if (cm.getActiveNetworkInfo().getType() == ConnectivityManager.TYPE_ETHERNET) {
                tvWifi.setImageDrawable(res.getDrawable(R.drawable.hm_lan));
            }
        }

        // takagen99: Set Style either Grid or Line
        if (Hawk.get(HawkConfig.HOME_REC_STYLE, false)) {
            tvStyle.setImageResource(R.drawable.hm_up_down);
        } else {
            tvStyle.setImageResource(R.drawable.hm_left_right);
        }

        mGridView.requestFocus();

        if (dataInitOk && jarInitOk) {
            sourceViewModel.getSort(ApiConfig.get().getHomeSourceBean().getKey());
            if (hasPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
                LOG.e("有");
            } else {
                LOG.e("无");
            }
            if (Hawk.get(HawkConfig.HOME_DEFAULT_SHOW, false)) {
                jumpActivity(LivePlayActivity.class);
            }         
            return;
        }
        tvNameAnimation();
        showLoading();
        if (dataInitOk && !jarInitOk) {
            if (!ApiConfig.get().getSpider().isEmpty()) {
                ApiConfig.get().loadJar(useCacheConfig, ApiConfig.get().getSpider(), new ApiConfig.LoadConfigCallback() {
                    @Override
                    public void success() {
                        jarInitOk = true;
                        mHandler.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                if (!useCacheConfig) {
                                    Toast.makeText(HomeActivity.this, getString(R.string.hm_ok), Toast.LENGTH_SHORT).show();
                                }
                                initData();
                            }
                        }, 50);
                    }

                    @Override
                    public void retry() {

                    }

                    @Override
                    public void error(String msg) {
                        jarInitOk = true;
                        dataInitOk = true;
                        mHandler.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                // jar 加载失败（含 md5 校验拒绝）不可由用户在启动时补救，
                                // 且不影响 JSON 源正常使用——安全闸门照常工作（已删文件拒载
                                // 并记日志），这里不再弹提示，避免每次冷启动打扰用户
                                LOG.e("Jar init failed (kept silent): " + msg);
                                initData();
                            }
                        },50);
                    }
                });
            }
            return;
        }
        ApiConfig.get().loadConfig(useCacheConfig, new ApiConfig.LoadConfigCallback() {
            TipDialog dialog = null;

            @Override
            public void retry() {
                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        initData();
                    }
                });
            }

            @Override
            public void success() {
                dataInitOk = true;
                if (ApiConfig.get().getSpider().isEmpty()) {
                    jarInitOk = true;
                }
                mHandler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        initData();
                    }
                }, 50);
            }

            @Override
            public void error(String msg) {
                if (msg.equalsIgnoreCase("-1")) {
                    mHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            dataInitOk = true;
                            jarInitOk = true;
                            initData();
                            showNoConfigGuide();
                        }
                    });
                    return;
                }
                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (dialog == null)
                            dialog = new TipDialog(HomeActivity.this, msg, getString(R.string.hm_retry), getString(R.string.hm_cancel), new TipDialog.OnListener() {
                                @Override
                                public void left() {
                                    mHandler.post(new Runnable() {
                                        @Override
                                        public void run() {
                                            initData();
                                            dialog.hide();
                                        }
                                    });
                                }

                                @Override
                                public void right() {
                                    dataInitOk = true;
                                    jarInitOk = true;
                                    mHandler.post(new Runnable() {
                                        @Override
                                        public void run() {
                                            initData();
                                            dialog.hide();
                                        }
                                    });
                                }

                                @Override
                                public void cancel() {
                                    dataInitOk = true;
                                    jarInitOk = true;
                                    mHandler.post(new Runnable() {
                                        @Override
                                        public void run() {
                                            initData();
                                            dialog.hide();
                                        }
                                    });
                                }
                            });
                        if (!dialog.isShowing())
                            dialog.show();
                    }
                });
            }
        }, this);
    }

    /**
     * 无配置时给出明确指引。
     *
     * <p>此前这条路径是静默的：直接 initData() 后首页只显示"没找到数据"，
     * 用户看不出是缺配置还是加载失败，也不知道该去哪里补。
     */
    private void showNoConfigGuide() {
        if (isFinishing() || noConfigGuideShown) return;
        noConfigGuideShown = true;
        final TipDialog[] holder = new TipDialog[1];
        holder[0] = new TipDialog(HomeActivity.this,
                getString(R.string.hm_no_config),
                getString(R.string.hm_no_config_goto),
                getString(R.string.hm_cancel),
                new TipDialog.OnListener() {
                    @Override
                    public void left() {
                        dismiss();
                        // 设置页已移除、固定内置配置：未加载到配置时只能重试
                        reloadHome();
                    }

                    @Override
                    public void right() {
                        dismiss();
                    }

                    @Override
                    public void cancel() {
                    }

                    private void dismiss() {
                        if (holder[0] != null && holder[0].isShowing()) {
                            holder[0].dismiss();
                        }
                    }
                });
        holder[0].show();
    }

    private void initViewPager(AbsSortXml absXml) {
        setPosterBrowseMode(false);
        if (sortAdapter.getData().size() > 0) {
            fragments.clear();
            currentSelected = Math.min(currentSelected, sortAdapter.getData().size() - 1);
            sortFocused = Math.min(sortFocused, sortAdapter.getData().size() - 1);
            for (MovieSort.SortData data : sortAdapter.getData()) {
                if (data.id.equals("my0")) {
                    if (Hawk.get(HawkConfig.HOME_REC, 0) == 1 && absXml != null && absXml.videoList != null && absXml.videoList.size() > 0) {
                        fragments.add(UserFragment.newInstance(absXml.videoList));
                    } else if (absXml != null && absXml.list != null && absXml.list.videoList != null && absXml.list.videoList.size() > 0) {
                        fragments.add(UserFragment.newInstance(absXml.list.videoList));
                    } else {
                        fragments.add(UserFragment.newInstance(null));
                    }
                } else {
                    fragments.add(GridFragment.newInstance(data));
                }
            }
            pageAdapter = new HomePageAdapter(getSupportFragmentManager(), fragments);
            try {
                Field field = ViewPager.class.getDeclaredField("mScroller");
                field.setAccessible(true);
                FixedSpeedScroller scroller = new FixedSpeedScroller(mContext, new AccelerateInterpolator());
                field.set(mViewPager, scroller);
                scroller.setmDuration(300);
            } catch (Exception e) {
            }
            mViewPager.setPageTransformer(true, new DefaultTransformer());
            mViewPager.setAdapter(pageAdapter);
            mViewPager.setCurrentItem(currentSelected, false);
            sortAdapter.setActivePosition(mGridView, currentSelected);
        }
    }

    @Override
    public void onBackPressed() {
        //打断加载
        if(isLoading()){
            refreshEmpty();
            return;
        }
        // 如果处于 VOD 删除模式，则退出该模式并刷新界面
        if (HawkConfig.hotVodDelete) {
            HawkConfig.hotVodDelete = false;
            UserFragment.homeHotVodAdapter.notifyDataSetChanged();
            return;
        }

        // 检查 fragments 状态
        if (this.fragments.size() <= 0 || this.sortFocused >= this.fragments.size() || this.sortFocused < 0) {
            doExit();
            return;
        }

        BaseLazyFragment baseLazyFragment = this.fragments.get(this.sortFocused);
        if (baseLazyFragment instanceof GridFragment) {
            GridFragment grid = (GridFragment) baseLazyFragment;
            // 如果当前 Fragment 能恢复之前保存的 UI 状态，则直接返回
            if (grid.restoreView()) {
                return;
            }
            if (posterBrowseMode || !mGridView.hasFocus()) {
                exitPosterBrowseAndFocusCategory();
                return;
            }
            // 如果当前不是第一个界面，则将列表设置到第一项
            if (this.sortFocused != 0) {
                this.mGridView.setSelection(0);
            } else {
                doExit();
            }
        } else if (posterBrowseMode) {
            exitPosterBrowseAndFocusCategory();
        } else if (baseLazyFragment instanceof UserFragment && UserFragment.tvHotListForGrid.canScrollVertically(-1)) {
            // 如果 UserFragment 列表可以向上滚动，则平滑滚动到顶部
            UserFragment.tvHotListForGrid.smoothScrollToPosition(0);
            this.mGridView.setSelection(0);
        } else {
            doExit();
        }
    }

    private void doExit() {
        // 如果两次返回间隔小于 2000 毫秒，则退出应用
        if (System.currentTimeMillis() - mExitTime < 2000) {
            AppManager.getInstance().finishAllActivity();
            EventBus.getDefault().unregister(this);
            ControlManager.get().stopServer();
            finish();
            android.os.Process.killProcess(android.os.Process.myPid());
            System.exit(0);
        } else {
            // 否则仅提示用户，再按一次退出应用
            mExitTime = System.currentTimeMillis();
            Toast.makeText(mContext, getString(R.string.hm_exit), Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();

        // takagen99 : Switch to show / hide source title
        SourceBean home = ApiConfig.get().getHomeSourceBean();
        if (Hawk.get(HawkConfig.HOME_SHOW_SOURCE, false)) {
            if (home != null && home.getName() != null && !home.getName().isEmpty()) {
                tvName.setText(home.getName());
                tvName.clearAnimation();
            }
        } else {
            tvName.setText(R.string.app_name);
        }

        // 顶部控制与时间元素按用户需求移除，始终保持隐藏
        if (tvFind != null) tvFind.setVisibility(View.GONE);
        if (tvMenu != null) tvMenu.setVisibility(View.GONE);
        if (tvWifi != null) tvWifi.setVisibility(View.GONE);
        if (tvStyle != null) tvStyle.setVisibility(View.GONE);
        if (tvDraw != null) tvDraw.setVisibility(View.GONE);
        if (tvDate != null) tvDate.setVisibility(View.GONE);
        MonetizationManager.get().showAd(this, contentLayout, AdPlacement.HOME_TOP_BANNER);
        if (posterBrowseMode) {
            View ad = contentLayout.findViewWithTag("commercial_ad_" + AdPlacement.HOME_TOP_BANNER.name());
            if (ad != null) ad.setVisibility(View.GONE);
        }
        UpdateManager.checkOnHome(this);
    }

    @Override
    protected void onPause() {
        super.onPause();
        mHandler.removeCallbacksAndMessages(null);
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void refresh(RefreshEvent event) {
        if (event.type == RefreshEvent.TYPE_PUSH_URL) {
            if (ApiConfig.get().getSource("push_agent") != null) {
                Intent newIntent = new Intent(mContext, DetailActivity.class);
                newIntent.putExtra("id", (String) event.obj);
                newIntent.putExtra("sourceKey", "push_agent");
                newIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                HomeActivity.this.startActivity(newIntent);
            }
        } else if (event.type == RefreshEvent.TYPE_FILTER_CHANGE) {
            if (currentView != null && currentSelected >= 0 && currentSelected < fragments.size()
                    && fragments.get(currentSelected) instanceof GridFragment) {
                GridFragment grid = (GridFragment) fragments.get(currentSelected);
                currentView.findViewById(R.id.tvFilter).setVisibility(grid.hasFilters() ? View.VISIBLE : View.GONE);
                if (grid.hasFilters()) showFilterIcon(grid.selectedFilterCount());
            }
        }
    }

    private void showFilterIcon(int count) {
        boolean activated = count > 0;
        currentView.findViewById(R.id.tvFilter).setVisibility(View.VISIBLE);
        ImageView imgView = currentView.findViewById(R.id.tvFilter);
        // 筛选图标只在聚焦的浅色胶囊上显示，未启用筛选时用深色才看得见
        imgView.setColorFilter(activated ? this.getThemeColor() : getResources().getColor(R.color.st_bg));
    }

    /** 把首页氛围背景切到指定海报；焦点停留一会儿才真正切换，可以在每次焦点变化时直接调用 */
    public void showAmbient(String picUrl) {
        if (ambientBackdrop != null) {
            ambientBackdrop.show(picUrl);
        }
    }

    private void initNavRail() {
        // 顺序与布局一致：搜索、历史、收藏、直播
        navRailItems = new View[]{findViewById(R.id.navSearch), findViewById(R.id.navHistory),
                findViewById(R.id.navCollect), findViewById(R.id.navLive)};
        View.OnClickListener click = v -> {
            HawkConfig.hotVodDelete = false;
            FastClickCheckUtil.check(v);
            int id = v.getId();
            if (id == R.id.navHistory) {
                jumpActivity(HistoryActivity.class);
            } else if (id == R.id.navLive) {
                jumpActivity(LivePlayActivity.class);
            } else if (id == R.id.navSearch) {
                jumpActivity(SearchActivity.class);
            } else if (id == R.id.navCollect) {
                jumpActivity(CollectActivity.class);
            }
        };
        View.OnFocusChangeListener focusAnim = (v, hasFocus) -> {
            if (hasFocus) {
                Motion.focusButton(v);
            } else {
                Motion.unfocus(v);
            }
        };
        View.OnKeyListener railKeys = (v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) {
                return false;
            }
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    return focusContent();
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    return true;
                case KeyEvent.KEYCODE_DPAD_UP:
                    if (v == navRailItems[0]) {
                        exitPosterBrowseAndFocusCategory();
                        return true;
                    }
                    return false;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    return v == navRailItems[navRailItems.length - 1];
                default:
                    return false;
            }
        };
        for (View item : navRailItems) {
            item.setOnClickListener(click);
            item.setOnFocusChangeListener(focusAnim);
            item.setOnKeyListener(railKeys);
        }
        // 长按“历史”：沿用原快捷入口的行为，切换首页推荐内容
        findViewById(R.id.navHistory).setOnLongClickListener(v -> {
            homeRecf();
            return reHome(mContext);
        });
        // 记录从内容区进入导航栏时的焦点，按右键原路返回
        getWindow().getDecorView().getViewTreeObserver().addOnGlobalFocusChangeListener((oldFocus, newFocus) -> {
            if (oldFocus != null && newFocus != null && isNavRailItem(newFocus) && isDescendant(mViewPager, oldFocus)) {
                lastContentFocus = oldFocus;
            }
        });
    }

    private boolean isNavRailItem(View v) {
        if (navRailItems == null) {
            return false;
        }
        for (View item : navRailItems) {
            if (item == v) {
                return true;
            }
        }
        return false;
    }

    private static boolean isDescendant(View ancestor, View v) {
        if (ancestor == null) {
            return false;
        }
        for (View cur = v; cur != null; ) {
            if (cur == ancestor) {
                return true;
            }
            cur = cur.getParent() instanceof View ? (View) cur.getParent() : null;
        }
        return false;
    }

    /** 内容区向左越界时调用：焦点进入左侧导航栏 */
    public boolean focusNavRail() {
        return navRailItems != null && navRailItems[0].requestFocus();
    }

    /** 导航栏 / 设置按钮向内容区移动：优先回到进入导航栏前的位置，否则落到当前页的默认入口 */
    public boolean focusContent() {
        if (currentSelected < 0 || currentSelected >= fragments.size()) {
            return mViewPager.requestFocus();
        }
        BaseLazyFragment fragment = fragments.get(currentSelected);
        View page = fragment.getView();
        if (lastContentFocus != null && lastContentFocus.isAttachedToWindow() && lastContentFocus.isShown()
                && isDescendant(page, lastContentFocus) && lastContentFocus.requestFocus()) {
            return true;
        }
        if (fragment instanceof UserFragment) {
            return ((UserFragment) fragment).focusHomeHeader() || mViewPager.requestFocus();
        }
        if (fragment instanceof GridFragment) {
            GridFragment grid = (GridFragment) fragment;
            return !grid.isLoad() || grid.focusSubCategoryOrGrid();
        }
        return mViewPager.requestFocus();
    }

    public boolean isPosterBrowseMode() {
        return posterBrowseMode;
    }

    /**
     * 海报浏览状态只是一个标记：不再隐藏顶栏、Hero 或子分类栏（那会让列表瞬间上移一屏造成跳帧）。
     * 首页顶栏的显隐由 {@link #setTopBarProgress(float)} 跟随列表滚动平滑过渡。
     */
    public void setPosterBrowseMode(boolean immersive) {
        if (posterBrowseMode == immersive) return;
        posterBrowseMode = immersive;
        updateFragmentPosterBrowseMode(currentSelected, immersive);
        if (sortFocused != currentSelected) {
            updateFragmentPosterBrowseMode(sortFocused, immersive);
        }
    }

    private void updateFragmentPosterBrowseMode(int position, boolean immersive) {
        if (position >= 0 && position < fragments.size()) {
            BaseLazyFragment fragment = fragments.get(position);
            if (fragment instanceof GridFragment) {
                ((GridFragment) fragment).setPosterBrowseMode(immersive);
            } else if (fragment instanceof UserFragment) {
                ((UserFragment) fragment).setPosterBrowseMode(immersive);
            }
        }
    }

    /**
     * 首页列表滚动时由 UserFragment 调用：progress 0 = 顶栏完全显示，1 = 完全收起。
     * 焦点在顶栏内时强制显示；完全收起后设为 INVISIBLE，避免焦点搜索落到看不见的 Tab 上误切页面。
     */
    // ---------------------------------------------------------------- 开屏动画

    /** 每个进程只播一次；从后台回来、重建首页不再播 */
    private static boolean splashPlayed = false;
    // 第四轮：整体再加长 1/3（1800→2400，兜底 4000→5300）
    private static final long SPLASH_MIN_MS = 2400;
    private static final long SPLASH_MAX_MS = 5300;
    private View splashOverlay;
    private long splashStartAt = 0;
    private boolean splashContentReady = false;
    private final Runnable splashDismiss = this::dismissSplash;

    private void startSplash() {
        splashOverlay = findViewById(R.id.splashOverlay);
        if (splashOverlay == null) return;
        if (splashPlayed) {
            splashOverlay.setVisibility(View.GONE);
            splashOverlay.post(this::showDisclaimerIfNeeded);
            return;
        }
        splashPlayed = true;
        splashStartAt = android.os.SystemClock.elapsedRealtime();
        View mark = findViewById(R.id.splashMark);
        View name = findViewById(R.id.splashName);
        // 图标旋转一圈出场（由小到大、由淡到实），随后字标从下往上升起
        mark.setRotation(-360f);
        mark.setScaleX(0.4f);
        mark.setScaleY(0.4f);
        mark.animate().alpha(1f).rotation(0f).scaleX(1f).scaleY(1f).setDuration(1280)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f)).start();
        name.setTranslationY(getResources().getDimension(R.dimen.vs_40));
        name.animate().alpha(1f).translationY(0f).setStartDelay(880).setDuration(960)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(1.4f)).start();
        // 兜底：数据迟迟不来也最多停留 5.3 秒
        splashOverlay.postDelayed(splashDismiss, SPLASH_MAX_MS);
    }

    /** 首页今日精选已有内容时由 UserFragment 调用：动画放满最短时长后立即淡出 */
    public void onHomeContentReady() {
        if (splashContentReady || splashOverlay == null || splashOverlay.getVisibility() != View.VISIBLE) return;
        splashContentReady = true;
        long wait = SPLASH_MIN_MS - (android.os.SystemClock.elapsedRealtime() - splashStartAt);
        splashOverlay.removeCallbacks(splashDismiss);
        splashOverlay.postDelayed(splashDismiss, Math.max(0, wait));
    }

    private void dismissSplash() {
        if (splashOverlay == null || splashOverlay.getVisibility() != View.VISIBLE) return;
        splashOverlay.removeCallbacks(splashDismiss);
        splashOverlay.animate().alpha(0f).setDuration(440).withEndAction(() -> {
            splashOverlay.setVisibility(View.GONE);
            // 遥控器设备：开屏结束后把焦点交给今日精选
            if (getCurrentFocus() == null) focusContent();
            showDisclaimerIfNeeded();
        }).start();
    }

    /** 首次使用先弹使用声明；同意后再做被它挡住的自动更新检查 */
    private void showDisclaimerIfNeeded() {
        com.github.tvbox.osc.ui.dialog.DisclaimerDialog.showIfNeeded(this,
                () -> UpdateManager.checkOnHome(this));
    }

    /** @return 实际生效的收起程度（焦点在顶栏时会被强制为 0） */
    public float setTopBarProgress(float progress) {
        if (topLayout == null) {
            return 0f;
        }
        float p = Math.max(0f, Math.min(1f, progress));
        // 遥控器焦点在顶栏 Tab 上时必须保持可见；触摸模式下 Tab 会一直“持有”焦点，不能据此强制显示
        if (topLayout.hasFocus() && !topLayout.isInTouchMode()) {
            p = 0f;
        }
        topBarProgress = p;
        float dy = -p * topLayout.getBottom();
        float alpha = 1f - p;
        for (View v : new View[]{topLayout, topScrim}) {
            if (v == null) continue;
            v.setTranslationY(dy);
            v.setAlpha(alpha);
            v.setVisibility(p >= 1f ? View.INVISIBLE : View.VISIBLE);
        }
        return p;
    }

    public void exitPosterBrowseAndFocusCategory() {
        setPosterBrowseMode(false);
        setTopBarProgress(0f);
        // 首页回到分类 Tab 时列表一并回顶，Hero 重新可见
        if (currentSelected >= 0 && currentSelected < fragments.size()
                && fragments.get(currentSelected) instanceof UserFragment) {
            ((UserFragment) fragments.get(currentSelected)).scrollToTop();
        }
        mGridView.post(() -> {
            setTopBarProgress(0f);
            if (currentSelected >= 0 && currentSelected < sortAdapter.getData().size()) {
                mGridView.setSelectedPosition(currentSelected);
                mGridView.setSelection(currentSelected);
                RecyclerView.LayoutManager lm = mGridView.getLayoutManager();
                View target = lm != null ? lm.findViewByPosition(currentSelected) : null;
                if (target != null && target.isAttachedToWindow()) {
                    sortFocusView = target;
                    target.requestFocus();
                    return;
                }
                mGridView.scrollToPosition(currentSelected);
            }
            if (sortFocusView != null && sortFocusView.isAttachedToWindow()) {
                sortFocusView.requestFocus();
            } else {
                mGridView.requestFocus();
            }
        });
    }

    private final Runnable mDataRunnable = new Runnable() {
        @Override
        public void run() {
            if (sortChange) {
                sortChange = false;
                if (sortFocused != currentSelected) {
                    currentSelected = sortFocused;
                    sortAdapter.setActivePosition(mGridView, currentSelected);
                    mViewPager.setCurrentItem(sortFocused, false);
                    changeTop(sortFocused != 0);
                    syncTopBarWithPage();
                }
            }
        }
    };

    public boolean isCurrentPage(BaseLazyFragment fragment) {
        return currentSelected >= 0 && currentSelected < fragments.size() && fragments.get(currentSelected) == fragment;
    }

    /** 切换分类页时同步顶栏：首页和分类页都按各自列表的滚动位置计算 */
    private void syncTopBarWithPage() {
        if (currentSelected >= 0 && currentSelected < fragments.size()
                && fragments.get(currentSelected) instanceof UserFragment) {
            ((UserFragment) fragments.get(currentSelected)).syncTopBar();
        } else if (currentSelected >= 0 && currentSelected < fragments.size()
                && fragments.get(currentSelected) instanceof GridFragment) {
            ((GridFragment) fragments.get(currentSelected)).syncTopBar();
        } else {
            setTopBarProgress(0f);
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        // 开屏动画期间吞掉按键，避免焦点在看不见的首页上乱跑
        if (splashOverlay != null && splashOverlay.getVisibility() == View.VISIBLE) {
            return true;
        }
        if (topHide < 0)
            return false;
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (event.getKeyCode() == KeyEvent.KEYCODE_MENU) {
                showSiteSwitch();
            }
//            if (event.getKeyCode() == KeyEvent.KEYCODE_DPAD_DOWN) {
//                if () {
//
//                }
//            }
        } else if (event.getAction() == KeyEvent.ACTION_UP) {

        }
        return super.dispatchKeyEvent(event);
    }

    byte topHide = 0;

    private void changeTop(boolean hide) {
        // topLayout 现已整合主分类栏 mGridViewCategory，切换大分类时顶部常驻，无需折叠隐藏
    }

    @Override
    protected void onDestroy() {
        // 视图树仍挂在窗口上时移除全局焦点监听
        if (ambientBackdrop != null) {
            ambientBackdrop.release();
        }
        if (posterFocus != null) {
            posterFocus.release();
        }
        super.onDestroy();
        try {
            EventBus.getDefault().unregister(this);
        } catch (Throwable ignored) {
        }
    }

    // Site Switch on Home Button
    void showSiteSwitch() {
        List<SourceBean> sites = new ArrayList<>();
        for (SourceBean sb : ApiConfig.get().getSourceBeanList()) {
            if (sb.getHide() == 0) sites.add(sb);
        }
        if (sites.size() > 0) {
            SelectDialog<SourceBean> dialog = new SelectDialog<>(HomeActivity.this);

            // Multi Column Selection
            int spanCount = (int) Math.floor(sites.size() / 10);
            if (spanCount <= 1) spanCount = 1;
            if (spanCount >= 3) spanCount = 3;

            TvRecyclerView tvRecyclerView = dialog.findViewById(R.id.list);
            tvRecyclerView.setLayoutManager(new V7GridLayoutManager(dialog.getContext(), spanCount));
            ConstraintLayout cl_root = dialog.findViewById(R.id.cl_root);
            ViewGroup.LayoutParams clp = cl_root.getLayoutParams();
            if (spanCount != 1) {
                clp.width = AutoSizeUtils.mm2px(dialog.getContext(), 400 + 260 * (spanCount - 1));
            }

            dialog.setTip(getString(R.string.dia_source));
            dialog.setAdapter(tvRecyclerView, new SelectDialogAdapter.SelectDialogInterface<SourceBean>() {
                @Override
                public void click(SourceBean value, int pos) {
                    ApiConfig.get().setSourceBean(value);
                    reloadHome();
                }

                @Override
                public String getDisplay(SourceBean val) {
                    return val.getName();
                }
            }, new DiffUtil.ItemCallback<SourceBean>() {
                @Override
                public boolean areItemsTheSame(@NonNull @NotNull SourceBean oldItem, @NonNull @NotNull SourceBean newItem) {
                    return oldItem == newItem;
                }

                @Override
                public boolean areContentsTheSame(@NonNull @NotNull SourceBean oldItem, @NonNull @NotNull SourceBean newItem) {
                    return oldItem.getKey().equals(newItem.getKey());
                }
            }, sites, sites.indexOf(ApiConfig.get().getHomeSourceBean()));
            dialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
                @Override
                public void onDismiss(DialogInterface dialog) {
//                    if (homeSourceKey != null && !homeSourceKey.equals(Hawk.get(HawkConfig.HOME_API, ""))) {
//                        Intent intent = new Intent(getApplicationContext(), HomeActivity.class);
//                        intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK);
//                        Bundle bundle = new Bundle();
//                        bundle.putBoolean("useCache", true);
//                        intent.putExtras(bundle);
//                        HomeActivity.this.startActivity(intent);
//                    }
                }
            });
            dialog.show();
        }
    }

    void reloadHome() {
        Intent intent = new Intent(getApplicationContext(), HomeActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK);
        Bundle bundle = new Bundle();
        bundle.putBoolean("useCache", true);
        intent.putExtras(bundle);
        HomeActivity.this.startActivity(intent);
    }

    private void refreshEmpty() {
        skipNextUpdate=true;
        showSuccess();
        sortAdapter.setNewData(DefaultConfig.adjustSort(ApiConfig.get().getHomeSourceBean().getKey(), new ArrayList<>(), true));
        initViewPager(null);
        tvName.clearAnimation();
    }

    private void tvNameAnimation()
    {
        AlphaAnimation blinkAnimation = new AlphaAnimation(0.0f, 1.0f);
        blinkAnimation.setDuration(500);
        blinkAnimation.setStartOffset(20);
        blinkAnimation.setRepeatMode(Animation.REVERSE);
        blinkAnimation.setRepeatCount(Animation.INFINITE);
        tvName.startAnimation(blinkAnimation);
    }
//    public void onClick(View v) {
//        FastClickCheckUtil.check(v);
//        if (v.getId() == R.id.tvFind) {
//            jumpActivity(SearchActivity.class);
//        } else if (v.getId() == R.id.tvMenu) {
//            jumpActivity(SettingActivity.class);
//        }
//    }

}
