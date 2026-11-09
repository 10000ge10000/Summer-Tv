package com.github.tvbox.osc.ui.activity;

import static xyz.doikki.videoplayer.util.PlayerUtils.stringForTimeVod;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.request.RequestOptions;
import com.chad.library.adapter.base.BaseQuickAdapter;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.base.App;
import com.github.tvbox.osc.base.BaseActivity;
import com.github.tvbox.osc.bean.Epginfo;
import com.github.tvbox.osc.bean.LiveChannelGroup;
import com.github.tvbox.osc.bean.LiveChannelItem;
import com.github.tvbox.osc.bean.LiveEpgDate;
import com.github.tvbox.osc.bean.LivePlayerManager;
import com.github.tvbox.osc.commercial.MonetizationManager;
import com.github.tvbox.osc.bean.LiveSettingGroup;
import com.github.tvbox.osc.bean.LiveSettingItem;
import com.github.tvbox.osc.player.controller.LiveController;
import com.github.tvbox.osc.ui.adapter.LiveChannelGroupAdapter;
import com.github.tvbox.osc.ui.adapter.LiveChannelItemAdapter;
import com.github.tvbox.osc.ui.adapter.LiveEpgAdapter;
import com.github.tvbox.osc.ui.adapter.LiveEpgDateAdapter;
import com.github.tvbox.osc.ui.adapter.LiveSettingGroupAdapter;
import com.github.tvbox.osc.ui.adapter.LiveSettingItemAdapter;
import com.github.tvbox.osc.ui.dialog.LivePasswordDialog;
import com.github.tvbox.osc.util.EpgUtil;
import com.github.tvbox.osc.util.FastClickCheckUtil;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.LiveSourceMemory;
import com.github.tvbox.osc.util.HawkUtils;
import com.github.tvbox.osc.util.JavaUtil;
import com.github.tvbox.osc.util.LOG;
import com.github.tvbox.osc.live.LiveCatchup;
import com.github.tvbox.osc.live.LiveChannelSwitcher;
import com.github.tvbox.osc.live.LiveRepository;
import com.lzy.okgo.OkGo;
import com.lzy.okgo.callback.StringCallback;
import com.lzy.okgo.model.Response;
import com.orhanobut.hawk.Hawk;
import com.owen.tvrecyclerview.widget.TvRecyclerView;
import com.owen.tvrecyclerview.widget.V7LinearLayoutManager;

import org.apache.commons.lang3.StringUtils;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.HashMap;
import java.util.Iterator;
import java.util.TimeZone;

import kotlin.Pair;
import xyz.doikki.videoplayer.player.VideoView;
import xyz.doikki.videoplayer.util.PlayerUtils;

/**
 * @author pj567
 * @date :2021/1/12
 * @description:
 */
public class LivePlayActivity extends BaseActivity {

    // Main View
    private VideoView mVideoView;
    private LiveController controller;

    // Left Channel View
    private LinearLayout tvLeftChannelListLayout;
    private TvRecyclerView mGroupGridView;
    private LinearLayout mDivLeft;
    private TvRecyclerView mChannelGridView;
    private LinearLayout mDivRight;
    private LinearLayout mGroupEPG;
    private TvRecyclerView mEpgDateGridView;
    private TvRecyclerView mEpgInfoGridView;
    // Left Channel View - Variables
    private LiveChannelGroupAdapter liveChannelGroupAdapter;
    private LiveChannelItemAdapter liveChannelItemAdapter;
    private final List<LiveChannelGroup> liveChannelGroupList = new ArrayList<>();
    private final List<LiveSettingGroup> liveSettingGroupList = new ArrayList<>();
    private int currentChannelGroupIndex = 0;
    private int currentLiveChannelIndex = -1;
    private LiveChannelItem currentLiveChannelItem = null;

    // 遥控器数字键输入的要切换的频道号码
    private int selectedChannelNumber = 0;
    private TextView tvSelectedChannel;

    // Right Channel View
    private LinearLayout tvRightSettingLayout;
    private TvRecyclerView mSettingGroupView;
    private TvRecyclerView mSettingItemView;
    // Right Channel View - Variables
    private LiveSettingGroupAdapter liveSettingGroupAdapter;
    private LiveSettingItemAdapter liveSettingItemAdapter;
    private final LivePlayerManager livePlayerManager = new LivePlayerManager();
    private final ArrayList<Integer> channelGroupPasswordConfirmed = new ArrayList<>();
    private final LiveChannelSwitcher channelSwitcher = LiveChannelSwitcher.create();

    // Bottom Channel View
    private LinearLayout tvBottomLayout;
    private ImageView tv_logo;
    private TextView tv_sys_time;
    private TextView tv_size;
    private TextView tv_source;
    // Bottom Channel View - Line 1 / 2 / 3
    private TextView tv_channelname;
    private TextView tv_channelnum;
    private TextView tv_curr_name;
    private TextView tv_curr_time;
    private TextView tv_next_name;
    private TextView tv_next_time;
    // Bottom Channel View - Variables
    private LiveEpgDateAdapter epgDateAdapter;
    private LiveEpgAdapter epgListAdapter;

    // Misc Variables
    public String epgStringAddress = "";
    SimpleDateFormat timeFormat = new SimpleDateFormat("yyyy-MM-dd");
    private final Handler mHandler = new Handler();
    private static LiveChannelItem channel_Name = null;
    // EPG 缓存：键为「频道名_yyyy-MM-dd」，限制条数避免长时间换台后无限增长
    private static final int EPG_CACHE_LIMIT = 64;
    private final LinkedHashMap<String, ArrayList<Epginfo>> hsEpg = new LinkedHashMap<String, ArrayList<Epginfo>>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(java.util.Map.Entry<String, ArrayList<Epginfo>> eldest) {
            return size() > EPG_CACHE_LIMIT;
        }
    };
    // 节目单日期列表中「今天」的下标（列表从 6 天前开始）
    private static final int EPG_TODAY_INDEX = 6;
    private boolean activityDestroyed = false;
    private TextView tvTime;
    private TextView tvNetSpeed;

    // Seek Bar
    boolean mIsDragging;
    LinearLayout llSeekBar;
    TextView mCurrentTime;
    SeekBar mSeekBar;
    TextView mTotalTime;
    boolean isVOD = false;

    // center BACK button
    LinearLayout mBack;

    private boolean isSHIYI = false;
    private static String shiyi_time;//时移时间

    private HashMap<String, String> setPlayHeaders(String url) {
        HashMap<String, String> header = new HashMap();
        try {
            boolean matchTo = false;
            JSONArray livePlayHeaders = new JSONArray(ApiConfig.get().getLivePlayHeaders().toString());
            for (int i = 0; i < livePlayHeaders.length(); i++) {
                JSONObject headerObj = livePlayHeaders.getJSONObject(i);
                JSONArray flags = headerObj.getJSONArray("flag");
                JSONObject headerData = headerObj.getJSONObject("header");
                for (int j = 0; j < flags.length(); j++) {
                    String flag = flags.getString(j);
                    if (url.contains(flag)) {
                        matchTo = true;
                        break;
                    }
                }
                if (matchTo) {
                    Iterator<String> keys = headerData.keys();
                    while (keys.hasNext()) {
                        String key = keys.next();
                        String value = headerData.getString(key);
                        header.put(key, value);
                    }
                    break;
                }
            }
            if (!matchTo) {
                header.put("User-Agent", App.DEFAULT_HTTP_UA);
            }
        } catch (Exception e) {
            LOG.e("Parse livePlayHeaders failed: " + e.getMessage());
        }
        // 原先在这里无条件覆盖 User-Agent，是为了适配那个已随 v1.0.17 移除的内置源。
        // 该写法让配置中的 livePlayHeaders 成为死代码：用户自有的、需要特定 UA 鉴权的
        // 直播源会拉流失败且无任何提示。现在只在没有任何 UA 时兜底。
        // m3u 为该线路指定了 User-Agent（user-agent 属性 / #EXTVLCOPT）时优先使用
        String lineUa = currentLiveChannelItem == null ? null : currentLiveChannelItem.getLineUserAgent();
        if (!StringUtils.isBlank(lineUa) && url != null && url.equals(currentLiveChannelItem.getUrl())) {
            header.put("User-Agent", lineUa);
        }
        if (!header.containsKey("User-Agent")) {
            header.put("User-Agent", App.DEFAULT_HTTP_UA);
        }
        return header;
    }

    @Override
    protected int getLayoutResID() {
        return R.layout.activity_live_play;
    }

    @Override
    protected void init() {

        // takagen99 : Hide only when video playing
        hideSystemUI(false);

        // Getting EPG Address
        epgStringAddress = Hawk.get(HawkConfig.EPG_URL, "");
        if (StringUtils.isBlank(epgStringAddress)) {
            epgStringAddress = "https://epg.112114.xyz/";
//            Hawk.put(HawkConfig.EPG_URL, epgStringAddress);
        }
        // http://epg.aishangtv.top/live_proxy_epg_bc.php
        // http://diyp.112114.xyz/

        setLoadSir(findViewById(R.id.live_root));
        mVideoView = findViewById(R.id.mVideoView);

        tvSelectedChannel = findViewById(R.id.tv_selected_channel);
        tv_size = findViewById(R.id.tv_size);                 // Resolution
        tv_source = findViewById(R.id.tv_source);             // Source/Total Source
        tv_sys_time = findViewById(R.id.tv_sys_time);         // System Time

        // VOD SeekBar
        llSeekBar = findViewById(R.id.ll_seekbar);
        mCurrentTime = findViewById(R.id.curr_time);
        mSeekBar = findViewById(R.id.seekBar);
        mTotalTime = findViewById(R.id.total_time);

        // Center Back Button
        mBack = findViewById(R.id.tvBackButton);
        mBack.setVisibility(View.INVISIBLE);

        // Bottom Info
        tvBottomLayout = findViewById(R.id.tvBottomLayout);
        tvBottomLayout.setVisibility(View.INVISIBLE);
        tv_channelname = findViewById(R.id.tv_channel_name);  //底部名称
        tv_channelnum = findViewById(R.id.tv_channel_number); //底部数字
        tv_logo = findViewById(R.id.tv_logo);
        tv_curr_time = findViewById(R.id.tv_current_program_time);
        tv_curr_name = findViewById(R.id.tv_current_program_name);
        tv_next_time = findViewById(R.id.tv_next_program_time);
        tv_next_name = findViewById(R.id.tv_next_program_name);

        // EPG Info
        mGroupEPG = findViewById(R.id.mGroupEPG);
        mDivRight = findViewById(R.id.mDivRight);
        mDivLeft = findViewById(R.id.mDivLeft);
        mEpgDateGridView = findViewById(R.id.mEpgDateGridView);
        mEpgInfoGridView = findViewById(R.id.mEpgInfoGridView);

        // Left Layout
        tvLeftChannelListLayout = findViewById(R.id.tvLeftChannelListLayout);
        mGroupGridView = findViewById(R.id.mGroupGridView);
        mChannelGridView = findViewById(R.id.mChannelGridView);

        // Right Layout
        tvRightSettingLayout = findViewById(R.id.tvRightSettingLayout);
        mSettingGroupView = findViewById(R.id.mSettingGroupView);
        mSettingItemView = findViewById(R.id.mSettingItemView);

        // Not in Used
        tvTime = findViewById(R.id.tvTime);
        tvNetSpeed = findViewById(R.id.tvNetSpeed);

        // Initialization
        initEpgDateView();
        initEpgListView();
        initVideoView();
        initChannelGroupView();
        initLiveChannelView();
        initSettingGroupView();
        initSettingItemView();
        initLiveChannelList();
        initLiveSettingGroupList();

        // takagen99 : Add SeekBar for VOD
        mSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser) {
                    return;
                }
                mHandler.removeCallbacks(mHideChannelInfoRun);
                mHandler.postDelayed(mHideChannelInfoRun, 6000);

                long duration = mVideoView.getDuration();
                long newPosition = (duration * progress) / seekBar.getMax();
                if (mCurrentTime != null)
                    mCurrentTime.setText(stringForTimeVod((int) newPosition));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                mIsDragging = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                mIsDragging = false;
                long duration = mVideoView.getDuration();
                long newPosition = (duration * seekBar.getProgress()) / seekBar.getMax();
                mVideoView.seekTo((int) newPosition);
            }
        });
        mSeekBar.setOnKeyListener(new View.OnKeyListener() {
            @Override
            public boolean onKey(View arg0, int keycode, KeyEvent event) {

                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    if (keycode == KeyEvent.KEYCODE_DPAD_LEFT || keycode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                        mIsDragging = true;
                    }
                } else if (event.getAction() == KeyEvent.ACTION_UP) {
                    mIsDragging = false;
                    long duration = mVideoView.getDuration();
                    long newPosition = (duration * mSeekBar.getProgress()) / mSeekBar.getMax();
                    mVideoView.seekTo((int) newPosition);
                }
                return false;
            }
        });
        // Button: BACK click to go back to previous page -------------------
        mBack.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                finish();
            }
        });
    }

    boolean PiPON = Hawk.get(HawkConfig.BACKGROUND_PLAY_TYPE, 0) == 2;

    // takagen99 : Enter PIP if supported
    @Override
    public void onUserLeaveHint() {
        if (supportsPiPMode() && PiPON) {
            // Hide controls when entering PIP
            mHandler.post(mHideChannelListRun);
            mHandler.post(mHideChannelInfoRun);
            mHandler.post(mHideSettingLayoutRun);
            enterPictureInPictureMode();
        }
    }

    @Override
    public void onBackPressed() {
        if (tvLeftChannelListLayout.getVisibility() == View.VISIBLE) {
            mHandler.removeCallbacks(mHideChannelListRun);
            mHandler.post(mHideChannelListRun);
        } else if (tvRightSettingLayout.getVisibility() == View.VISIBLE) {
            mHandler.removeCallbacks(mHideSettingLayoutRun);
            mHandler.post(mHideSettingLayoutRun);
        } else if (tvBottomLayout.getVisibility() == View.VISIBLE) {
            mHandler.removeCallbacks(mHideChannelInfoRun);
            mHandler.post(mHideChannelInfoRun);
        } else {
            mHandler.removeCallbacks(mConnectTimeoutChangeSourceRun);
            mHandler.removeCallbacks(mPlayPendingChannel);
            mHandler.removeCallbacks(mUpdateNetSpeedRun);
            mHandler.removeCallbacks(mUpdateTimeRun);
            mHandler.removeCallbacks(tv_sys_timeRunnable);
            exit();
        }
    }

    private long mExitTime = 0;

    private void exit() {
        if (System.currentTimeMillis() - mExitTime < 2000) {
            super.onBackPressed();
        } else {
            mExitTime = System.currentTimeMillis();
            Toast.makeText(mContext, getString(R.string.hm_exit_live), Toast.LENGTH_SHORT).show();
        }
    }

    private final Runnable mPlaySelectedChannel = new Runnable() {
        @Override
        public void run() {
            tvSelectedChannel.setVisibility(View.GONE);
            tvSelectedChannel.setText("");

            int number = selectedChannelNumber;
            selectedChannelNumber = 0;
            if (number <= 0) return;
            // 频道号是全局连续编号（与列表显示一致），按号码在所有分组中查找
            int[][] nums = new int[liveChannelGroupList.size()][];
            for (int g = 0; g < liveChannelGroupList.size(); g++) {
                ArrayList<LiveChannelItem> items = liveChannelGroupList.get(g).getLiveChannels();
                int size = items == null ? 0 : items.size();
                nums[g] = new int[size];
                for (int i = 0; i < size; i++) nums[g][i] = items.get(i).getChannelNum();
            }
            int[] target = LiveChannelSwitcher.findByNumber(nums, number);
            if (target == null) {
                Toast.makeText(mContext, getString(R.string.live_channel_number_missing, number), Toast.LENGTH_SHORT).show();
                return;
            }
            if (isNeedInputPassword(target[0])) {
                selectChannelGroup(target[0], false, target[1]);
            } else {
                playChannel(target[0], target[1], false);
            }
        }
    };

    private void numericKeyDown(int digit) {
        // 最多 4 位，避免一直按数字键导致整型溢出
        if (selectedChannelNumber >= 1000) selectedChannelNumber = 0;
        selectedChannelNumber = selectedChannelNumber * 10 + digit;

        tvSelectedChannel.setText(Integer.toString(selectedChannelNumber));
        tvSelectedChannel.setVisibility(View.VISIBLE);

        mHandler.removeCallbacks(mPlaySelectedChannel);
        mHandler.postDelayed(mPlaySelectedChannel, 2000);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            int keyCode = event.getKeyCode();
            if (keyCode == KeyEvent.KEYCODE_MENU) {
                showSettingGroup();
            } else if (!isListOrSettingLayoutVisible()) {
                switch (keyCode) {
                    case KeyEvent.KEYCODE_DPAD_UP:
                        if (Hawk.get(HawkConfig.LIVE_CHANNEL_REVERSE, false))
                            playNext();
                        else
                            playPrevious();
                        break;
                    case KeyEvent.KEYCODE_DPAD_DOWN:
                        if (Hawk.get(HawkConfig.LIVE_CHANNEL_REVERSE, false))
                            playPrevious();
                        else
                            playNext();
                        break;
                    case KeyEvent.KEYCODE_DPAD_LEFT:
                        // takagen99 : To cater for newer Android w no Menu button
                        // playPreSource();
                        if (!isVOD) {
                            showSettingGroup();
                        } else {
                            showChannelInfo();
                        }
                        break;
                    case KeyEvent.KEYCODE_DPAD_RIGHT:
                        if (!isVOD) {
                            playNextSource();
                        } else {
                            showChannelInfo();
                        }
                        break;
                    case KeyEvent.KEYCODE_DPAD_CENTER:
                    case KeyEvent.KEYCODE_ENTER:
                    case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                        showChannelList();
                        break;
                    default:
                        if (keyCode >= KeyEvent.KEYCODE_0 && keyCode <= KeyEvent.KEYCODE_9) {
                            keyCode -= KeyEvent.KEYCODE_0;
                        } else if (keyCode >= KeyEvent.KEYCODE_NUMPAD_0 && keyCode <= KeyEvent.KEYCODE_NUMPAD_9) {
                            keyCode -= KeyEvent.KEYCODE_NUMPAD_0;
                        } else {
                            break;
                        }
                        numericKeyDown(keyCode);
                }
            }
        } else if (event.getAction() == KeyEvent.ACTION_UP) {
        }
        return super.dispatchKeyEvent(event);
    }

    // takagen99 : Use onStopCalled to track close activity
    private boolean onStopCalled;

    @Override
    protected void onResume() {
        super.onResume();
        if (mVideoView != null) {
            mVideoView.resume();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        onStopCalled = true;
        LiveSourceMemory.flush();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (mVideoView != null) {
            if (supportsPiPMode()) {
                if (isInPictureInPictureMode()) {
                    // Continue playback
                    mVideoView.resume();
                } else {
                    // Pause playback
                    mVideoView.pause();
                }
            } else {
                mVideoView.pause();
            }
        }
    }

    // takagen99 : PIP fix to close video when close window
    @Override
    public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode);
        if (supportsPiPMode()) {
            if (!isInPictureInPictureMode()) {
                // Closed playback
                if (onStopCalled) {
                    mVideoView.release();
                }
            }
        }
    }

    @Override
    protected void onDestroy() {
        activityDestroyed = true;
        // 清掉所有待执行的换源 / 计时 / 隐藏浮层任务，并取消本页发出的网络请求
        mHandler.removeCallbacksAndMessages(null);
        OkGo.getInstance().cancelTag(this);
        super.onDestroy();
        if (mVideoView != null) {
            mVideoView.release();
            mVideoView = null;
        }
    }

    private void showChannelList() {
        mBack.setVisibility(View.INVISIBLE);
        if (tvBottomLayout.getVisibility() == View.VISIBLE) {
            mHandler.removeCallbacks(mHideChannelInfoRun);
            mHandler.post(mHideChannelInfoRun);
        } else if (tvRightSettingLayout.getVisibility() == View.VISIBLE) {
            mHandler.removeCallbacks(mHideSettingLayoutRun);
            mHandler.post(mHideSettingLayoutRun);
        } else if (tvLeftChannelListLayout.getVisibility() == View.INVISIBLE & tvRightSettingLayout.getVisibility() == View.INVISIBLE) {
            //重新载入上一次状态
            liveChannelItemAdapter.setNewData(getLiveChannels(currentChannelGroupIndex));
            if (currentLiveChannelIndex > -1)
                mChannelGridView.scrollToPosition(currentLiveChannelIndex);
            mChannelGridView.setSelection(currentLiveChannelIndex);
            mGroupGridView.scrollToPosition(currentChannelGroupIndex);
            mGroupGridView.setSelection(currentChannelGroupIndex);
            mHandler.postDelayed(mFocusCurrentChannelAndShowChannelList, 200);
            startSysTimeTicker();
        } else {
            mBack.setVisibility(View.INVISIBLE);
            mHandler.removeCallbacks(mHideChannelListRun);
            mHandler.post(mHideChannelListRun);
            mHandler.removeCallbacks(tv_sys_timeRunnable);
        }
    }

    //频道列表
    public void divLoadEpgR(View view) {
        mGroupGridView.setVisibility(View.GONE);
        mEpgInfoGridView.setVisibility(View.VISIBLE);
        mGroupEPG.setVisibility(View.VISIBLE);
        mDivLeft.setVisibility(View.VISIBLE);
        mDivRight.setVisibility(View.GONE);
        tvLeftChannelListLayout.setVisibility(View.INVISIBLE);
        showChannelList();
    }

    public void divLoadEpgL(View view) {
        mGroupGridView.setVisibility(View.VISIBLE);
        mEpgInfoGridView.setVisibility(View.GONE);
        mGroupEPG.setVisibility(View.GONE);
        mDivLeft.setVisibility(View.GONE);
        mDivRight.setVisibility(View.VISIBLE);
        tvLeftChannelListLayout.setVisibility(View.INVISIBLE);
        showChannelList();
    }

    private final Runnable mFocusCurrentChannelAndShowChannelList = new Runnable() {
        @Override
        public void run() {
            if (mGroupGridView.isScrolling() || mChannelGridView.isScrolling() || mGroupGridView.isComputingLayout() || mChannelGridView.isComputingLayout()) {
                mHandler.postDelayed(this, 100);
            } else {
                liveChannelGroupAdapter.setSelectedGroupIndex(currentChannelGroupIndex);
                liveChannelItemAdapter.setSelectedChannelIndex(currentLiveChannelIndex);
                RecyclerView.ViewHolder holder = mChannelGridView.findViewHolderForAdapterPosition(currentLiveChannelIndex);
                if (holder != null)
                    holder.itemView.requestFocus();
                tvLeftChannelListLayout.setVisibility(View.VISIBLE);
                tvLeftChannelListLayout.setAlpha(0.0f);
                tvLeftChannelListLayout.setTranslationX(-tvLeftChannelListLayout.getWidth() / 2);
                tvLeftChannelListLayout.animate()
                        .translationX(0)
                        .alpha(1.0f)
                        .setDuration(250)
                        .setInterpolator(new DecelerateInterpolator())
                        .setListener(null);
                mHandler.removeCallbacks(mHideChannelListRun);
                mHandler.postDelayed(mHideChannelListRun, 6000);
                mHandler.postDelayed(mUpdateLayout, 255);   // Workaround Fix : SurfaceView
            }
        }
    };

    private final Runnable mUpdateLayout = new Runnable() {
        @Override
        public void run() {
            tvLeftChannelListLayout.requestLayout();
            tvRightSettingLayout.requestLayout();
        }
    };

    private final Runnable mHideChannelListRun = new Runnable() {
        @Override
        public void run() {
            ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) tvLeftChannelListLayout.getLayoutParams();
            if (tvLeftChannelListLayout.getVisibility() == View.VISIBLE) {
                tvLeftChannelListLayout.animate()
                        .translationX(-tvLeftChannelListLayout.getWidth() / 2)
                        .alpha(0.0f)
                        .setDuration(250)
                        .setInterpolator(new DecelerateInterpolator())
                        .setListener(new AnimatorListenerAdapter() {
                            @Override
                            public void onAnimationEnd(Animator animation) {
                                super.onAnimationEnd(animation);
                                tvLeftChannelListLayout.setVisibility(View.INVISIBLE);
                                tvLeftChannelListLayout.clearAnimation();
                            }
                        });
            }
        }
    };

    private void showChannelInfo() {
        // takagen99: Check if Touch Screen, show back button
        if (supportsTouch()) {
            mBack.setVisibility(View.VISIBLE);
        }

        if (tvBottomLayout.getVisibility() == View.GONE || tvBottomLayout.getVisibility() == View.INVISIBLE) {
            tvBottomLayout.setVisibility(View.VISIBLE);
            tvBottomLayout.setTranslationY(tvBottomLayout.getHeight() / 2);
            tvBottomLayout.setAlpha(0.0f);
            tvBottomLayout.animate()
                    .alpha(1.0f)
                    .setDuration(250)
                    .setInterpolator(new DecelerateInterpolator())
                    .translationY(0)
                    .setListener(null);
        }
        mHandler.removeCallbacks(mHideChannelInfoRun);
        mHandler.postDelayed(mHideChannelInfoRun, 6000);
        mHandler.postDelayed(mUpdateLayout, 255);   // Workaround Fix : SurfaceView
    }

    private final Runnable mHideChannelInfoRun = new Runnable() {
        @Override
        public void run() {
            mBack.setVisibility(View.INVISIBLE);
            if (tvBottomLayout.getVisibility() == View.VISIBLE) {
                tvBottomLayout.animate()
                        .alpha(0.0f)
                        .setDuration(250)
                        .setInterpolator(new DecelerateInterpolator())
                        .translationY(tvBottomLayout.getHeight() / 2)
                        .setListener(new AnimatorListenerAdapter() {
                            @Override
                            public void onAnimationEnd(Animator animation) {
                                super.onAnimationEnd(animation);
                                tvBottomLayout.setVisibility(View.INVISIBLE);
                                tvBottomLayout.clearAnimation();
                            }
                        });
            }
        }
    };

    private void toggleChannelInfo() {
        if (tvLeftChannelListLayout.getVisibility() == View.VISIBLE) {
            mHandler.removeCallbacks(mHideChannelListRun);
            mHandler.post(mHideChannelListRun);
        } else if (tvRightSettingLayout.getVisibility() == View.VISIBLE) {
            mHandler.removeCallbacks(mHideSettingLayoutRun);
            mHandler.post(mHideSettingLayoutRun);
        } else if (tvBottomLayout.getVisibility() == View.INVISIBLE) {
            showChannelInfo();
        } else {
            mBack.setVisibility(View.INVISIBLE);
            mHandler.removeCallbacks(mHideChannelInfoRun);
            mHandler.post(mHideChannelInfoRun);
            mHandler.post(mUpdateLayout);   // Workaround Fix : SurfaceView
        }
    }

    //显示侧边EPG
    private void showEpg(Date date, ArrayList<Epginfo> arrayList) {
        if (arrayList != null && arrayList.size() > 0) {
            epgdata = arrayList;
            epgListAdapter.CanBack(currentLiveChannelItem.getinclude_back());
            epgListAdapter.setNewData(epgdata);

            int i = -1;
            int size = epgdata.size() - 1;
            while (size >= 0) {
                if (new Date().compareTo(epgdata.get(size).startdateTime) >= 0) {
                    break;
                }
                size--;
            }
            i = size;
            if (i >= 0 && new Date().compareTo(epgdata.get(i).enddateTime) <= 0) {
                mEpgInfoGridView.setSelectedPosition(i);
                mEpgInfoGridView.setSelection(i);
                epgListAdapter.setSelectedEpgIndex(i);
                int finalI = i;
                mEpgInfoGridView.post(new Runnable() {
                    @Override
                    public void run() {
                        mEpgInfoGridView.smoothScrollToPosition(finalI);
                    }
                });
            }
        } else {

            if (arrayList == null) arrayList = new ArrayList<>();
            Epginfo epgbcinfo = new Epginfo(date, getString(R.string.live_epg_empty), date, "00:00", "23:59", 0);
            arrayList.add(epgbcinfo);
            epgdata = arrayList;
            epgListAdapter.setNewData(epgdata);

            //  mEpgInfoGridView.setAdapter(epgListAdapter);
        }
    }

    private final Runnable tv_sys_timeRunnable = new Runnable() {
        @Override
        public void run() {
            Date date = new Date();
            SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss", Locale.ENGLISH);
            tv_sys_time.setText(timeFormat.format(date));
            mHandler.postDelayed(this, 1000);

            // takagen99 : Update SeekBar
            if (mVideoView != null & !mIsDragging) {
                int currentPosition = (int) mVideoView.getCurrentPosition();
                mCurrentTime.setText(stringForTimeVod(currentPosition));
                mSeekBar.setProgress(currentPosition);
            }
        }
    };

    /** 先移除再投递，避免每次换台都叠加一个每秒执行的计时任务 */
    private void startSysTimeTicker() {
        mHandler.removeCallbacks(tv_sys_timeRunnable);
        mHandler.post(tv_sys_timeRunnable);
    }

    //显示底部EPG
    private void showBottomEpg() {
        showBottomEpg(true);
    }

    /**
     * 底部信息栏只展示「今天」的当前 / 下一个节目，与左侧节目单选中的日期无关。
     *
     * @param allowFetch 缓存未命中时是否发起请求；EPG 回调里传 false，避免请求失败后反复重试
     */
    private void showBottomEpg(boolean allowFetch) {
        if (isSHIYI || channel_Name == null || channel_Name.getChannelName() == null)
            return;
        showChannelInfo();
        String channelName = channel_Name.getChannelName();
        String[] epgInfo = EpgUtil.getEpgInfo(epgLookupName(channel_Name));
        getTvLogo(channelName, logoUrlOf(channel_Name, epgInfo));
        ArrayList<Epginfo> arrayList = hsEpg.get(epgCacheKey(channelName, new Date()));
        if (arrayList == null) {
            if (allowFetch) getEpg(new Date());
            return;
        }
        tv_curr_time.setText("");
        tv_curr_name.setText("");
        tv_next_time.setText("");
        tv_next_name.setText("");
        if (!arrayList.isEmpty()) {
            Date date = new Date();
            for (int size = arrayList.size() - 1; size >= 0; size--) {
                Epginfo info = arrayList.get(size);
                if (date.after(info.startdateTime) && date.before(info.enddateTime)) {
                    tv_curr_time.setText(info.start + " - " + info.end);
                    tv_curr_name.setText(info.title);
                    if (size != arrayList.size() - 1) {
                        Epginfo next = arrayList.get(size + 1);
                        tv_next_time.setText(next.start + " - " + next.end);
                        tv_next_name.setText(next.title);
                    } else {
                        tv_next_time.setText("00:00 - 23:59");
                        tv_next_name.setText(getString(R.string.live_epg_no_info));
                    }
                    break;
                }
            }
        }
    }

    /** EPG 缓存键：频道名 + 节目日期（而不是「今天 / 星期几」这类会随日期漂移的标签） */
    private static String epgCacheKey(String channelName, Date date) {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT);
        format.setTimeZone(TimeZone.getTimeZone("GMT+8:00"));
        return channelName + "_" + format.format(date);
    }

    /** 节目单当前选中的日期；未选中时视为今天 */
    private Date selectedEpgDate() {
        int selectedIndex = epgDateAdapter.getSelectedIndex();
        if (selectedIndex < 0 || selectedIndex >= epgDateAdapter.getData().size()) return new Date();
        return epgDateAdapter.getData().get(selectedIndex).getDateParamVal();
    }

    /** 查 EPG 用的频道名：优先频道名本身，查不到再用 m3u 里的 tvg-name */
    private static String epgLookupName(LiveChannelItem item) {
        String name = item.getChannelName();
        if (EpgUtil.getEpgInfo(name) == null && !StringUtils.isBlank(item.getTvgName())) {
            return item.getTvgName();
        }
        return name;
    }

    private static String logoUrlOf(LiveChannelItem item, String[] epgInfo) {
        if (epgInfo != null && !StringUtils.isBlank(epgInfo[0])) return epgInfo[0];
        return StringUtils.isBlank(item.getLogo()) ? null : item.getLogo();
    }

    // 获取EPG并存储 // 百川epg
    private List<Epginfo> epgdata = new ArrayList<>();

    // Get Channel Logo
    private void getTvLogo(String channelName, String logoUrl) {
        // takagen99 : Use Glide instead
        RequestOptions options = new RequestOptions();
        options.diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                .placeholder(R.drawable.img_logo_placeholder);
        Glide.with(App.getInstance())
                .load(logoUrl)
                .apply(options)
                .into(tv_logo);
    }

    public void getEpg(Date date) {
        if (channel_Name == null) return;
        // 记下发起请求时的频道：快速换台时旧请求的响应只写缓存，不覆盖新频道的界面
        final LiveChannelItem requestChannel = channel_Name;
        final String channelName = requestChannel.getChannelName();
        final String cacheKey = epgCacheKey(channelName, date);
        SimpleDateFormat timeFormat = new SimpleDateFormat("yyyy-MM-dd");
        timeFormat.setTimeZone(TimeZone.getTimeZone("GMT+8:00"));
        String[] epgInfo = EpgUtil.getEpgInfo(epgLookupName(requestChannel));
        String epgTagName = epgLookupName(requestChannel);
        getTvLogo(channelName, logoUrlOf(requestChannel, epgInfo));
        if (epgInfo != null && !epgInfo[1].isEmpty()) {
            epgTagName = epgInfo[1];
        }
        epgListAdapter.CanBack(requestChannel.getinclude_back());

        ArrayList<Epginfo> cached = hsEpg.get(cacheKey);
        if (cached != null) {
            if (isSameDay(date, selectedEpgDate())) showEpg(date, new ArrayList<>(cached));
            return;
        }

        String epgUrl;
        if (epgStringAddress.contains("{name}") && epgStringAddress.contains("{date}")) {
            epgUrl = epgStringAddress.replace("{name}", URLEncoder.encode(epgTagName)).replace("{date}", timeFormat.format(date));
        } else {
            epgUrl = epgStringAddress + "?ch=" + URLEncoder.encode(epgTagName) + "&date=" + timeFormat.format(date);
        }
        OkGo.<String>get(epgUrl).tag(this).execute(new StringCallback() {
            @Override
            public void onSuccess(Response<String> response) {
                if (activityDestroyed) return;
                String paramString = response.body();
                ArrayList<Epginfo> arrayList = new ArrayList<>();
                try {
                    if (paramString != null && paramString.contains("epg_data")) {
                        final JSONArray jSONArray = new JSONObject(paramString).optJSONArray("epg_data");
                        if (jSONArray != null)
                            for (int b = 0; b < jSONArray.length(); b++) {
                                JSONObject jSONObject = jSONArray.getJSONObject(b);
                                Epginfo epgbcinfo = new Epginfo(date, jSONObject.optString("title"), date, jSONObject.optString("start"), jSONObject.optString("end"), b);
                                arrayList.add(epgbcinfo);
                            }
                    }
                } catch (JSONException jSONException) {
                    jSONException.printStackTrace();
                }
                hsEpg.put(cacheKey, arrayList);
                if (requestChannel != channel_Name) return;
                if (isSameDay(date, selectedEpgDate())) showEpg(date, new ArrayList<>(arrayList));
                if (isSameDay(date, new Date())) showBottomEpg(false);
            }

            @Override
            public void onError(Response<String> response) {
                super.onError(response);
                if (activityDestroyed || requestChannel != channel_Name) return;
                if (isSameDay(date, selectedEpgDate())) showEpg(date, new ArrayList<Epginfo>());
            }
        });
    }

    private static boolean isSameDay(Date left, Date right) {
        return epgCacheKey("", left).equals(epgCacheKey("", right));
    }

    //节目播放
    private boolean playChannel(int channelGroupIndex, int liveChannelIndex, boolean changeSource) {
        if ((channelGroupIndex == currentChannelGroupIndex && liveChannelIndex == currentLiveChannelIndex && !changeSource)
                || (changeSource && currentLiveChannelItem.getSourceNum() == 1)) {
            showChannelInfo();
            return true;
        }
        if (mVideoView == null) return true;
        if (!changeSource) {
            ArrayList<LiveChannelItem> channels = getLiveChannels(channelGroupIndex);
            if (liveChannelIndex < 0 || liveChannelIndex >= channels.size()) return true;
            currentChannelGroupIndex = channelGroupIndex;
            currentLiveChannelIndex = liveChannelIndex;
            currentLiveChannelItem = channels.get(currentLiveChannelIndex);
            Hawk.put(HawkConfig.LIVE_CHANNEL, currentLiveChannelItem.getChannelName());
            HawkUtils.setLastLiveChannelGroup(liveChannelGroupList.get(currentChannelGroupIndex).getGroupName());
            // 换台（手动或数字选台）重新开始计数换线次数
            channelSwitcher.resetForChannel(currentLiveChannelItem.getSourceNum());
        }
        startPlayback();
        return true;
    }

    /** 播放当前频道的当前线路（换台、换线共用） */
    private void startPlayback() {
        if (mVideoView == null || currentLiveChannelItem == null) return;
        mHandler.removeCallbacks(mConnectTimeoutChangeSourceRun);
        mVideoView.release();
        resetCatchupState();
        // release() destroys the current player instance. Recreate it for both
        // channel changes and source changes before assigning the new URL.
        livePlayerManager.getLiveChannelPlayer(mVideoView, currentLiveChannelItem.getChannelKey());
        channel_Name = currentLiveChannelItem;
        String liveUrl = currentLiveChannelItem.getUrl();
        currentLiveChannelItem.setinclude_back(supportsCatchup(currentLiveChannelItem, liveUrl));

        // takagen99 : Moved update of Channel Info here before getting EPG (no dependency on EPG)
        startSysTimeTicker();
        updateChannelInfoText(channel_Name);

        getEpg(new Date());
        Uri liveUri = Uri.parse(liveUrl);
        LOG.i("Live source selected: channel=" + currentLiveChannelItem.getChannelName()
                + ", line=" + (currentLiveChannelItem.getSourceIndex() + 1)
                + ", host=" + liveUri.getHost()
                + ", path=" + liveUri.getPath());
        playStartedAt = android.os.SystemClock.elapsedRealtime();
        playSucceeded = false;
        mVideoView.setUrl(liveUrl, setPlayHeaders(liveUrl));
        showChannelInfo();
        mVideoView.start();
    }

    /** 底部信息栏的频道名、号码和线路 */
    private void updateChannelInfoText(LiveChannelItem item) {
        tv_channelname.setText(item.getChannelName());
        tv_channelnum.setText(String.valueOf(item.getChannelNum()));
        if (item.getSourceNum() <= 0) {
            tv_source.setText("1/1");
        } else {
            tv_source.setText(getString(R.string.live_line_format, item.getSourceIndex() + 1, item.getSourceNum()));
        }
    }

    /** 华为 PLTV/8888 回看地址，或 m3u 声明了 playseek 形式的 catchup-source */
    private static boolean supportsCatchup(LiveChannelItem item, String url) {
        if (url != null && url.contains("PLTV/8888")) return true;
        String source = item.getCatchupSource();
        return source != null && source.contains("playseek");
    }

    /** 换台 / 换线后退出回看状态，节目单回到今天 */
    private void resetCatchupState() {
        isSHIYI = false;
        shiyi_time = null;
        epgDateAdapter.setSelectedIndex(EPG_TODAY_INDEX);
        epgListAdapter.setShiyiSelection(-1, false, timeFormat.format(new Date()));
    }

    private void playNext() {
        switchChannelDebounced(1);
    }

    private void playPrevious() {
        switchChannelDebounced(-1);
    }

    /**
     * 上 / 下换台防抖：连续按键只移动「目标频道」并刷新底部信息，停手 300ms 后才真正起播，
     * 避免快速翻台时每一下都重建播放器。
     */
    private void switchChannelDebounced(int direction) {
        if (!isCurrentLiveChannelValid()) return;
        int fromGroup = pendingGroupIndex >= 0 ? pendingGroupIndex : currentChannelGroupIndex;
        int fromChannel = pendingGroupIndex >= 0 ? pendingChannelIndex : currentLiveChannelIndex;
        Integer[] target = getNextChannel(fromGroup, fromChannel, direction);
        pendingGroupIndex = target[0];
        pendingChannelIndex = target[1];
        ArrayList<LiveChannelItem> channels = getLiveChannels(pendingGroupIndex);
        if (pendingChannelIndex >= 0 && pendingChannelIndex < channels.size()) {
            updateChannelInfoText(channels.get(pendingChannelIndex));
            showChannelInfo();
        }
        mHandler.removeCallbacks(mPlayPendingChannel);
        mHandler.postDelayed(mPlayPendingChannel, CHANNEL_SWITCH_DEBOUNCE_MS);
    }

    private static final long CHANNEL_SWITCH_DEBOUNCE_MS = 300;
    private int pendingGroupIndex = -1;
    private int pendingChannelIndex = -1;

    private final Runnable mPlayPendingChannel = new Runnable() {
        @Override
        public void run() {
            int g = pendingGroupIndex;
            int i = pendingChannelIndex;
            pendingGroupIndex = -1;
            pendingChannelIndex = -1;
            if (g < 0) return;
            if (g == currentChannelGroupIndex && i == currentLiveChannelIndex) {
                updateChannelInfoText(currentLiveChannelItem);
                return;
            }
            playChannel(g, i, false);
        }
    };

    public void playPreSource() {
        if (!isCurrentLiveChannelValid()) return;
        currentLiveChannelItem.preSource();
        rememberLiveSourceIndex(currentLiveChannelItem.getSourceIndex());
        channelSwitcher.onManualSourceChange();
        playChannel(currentChannelGroupIndex, currentLiveChannelIndex, true);
    }

    public void playNextSource() {
        if (mVideoView == null) {
            return;
        }
        if (!isCurrentLiveChannelValid()) return;
        currentLiveChannelItem.nextSource();
        rememberLiveSourceIndex(currentLiveChannelItem.getSourceIndex());
        channelSwitcher.onManualSourceChange();
        playChannel(currentChannelGroupIndex, currentLiveChannelIndex, true);
    }

    /**
     * 只记住当前频道的线路选择。
     *
     * <p>原实现把该索引写进全局并同步到所有频道——线路序号在不同频道之间
     * 并无对应关系，用户为某个卡顿频道换线路会连带改掉其他所有频道。
     */
    private void rememberLiveSourceIndex(int sourceIndex) {
        if (sourceIndex < 0) sourceIndex = 0;
        if (currentLiveChannelItem == null || currentLiveChannelItem.getSourceNum() <= 0) return;
        int applied = Math.min(sourceIndex, currentLiveChannelItem.getSourceNum() - 1);
        currentLiveChannelItem.setSourceIndex(applied);
        // 按线路 URL 记忆，合并 / 新增线路后不会错位
        LiveSourceMemory.rememberUrl(currentLiveChannelItem.getChannelKey(), currentLiveChannelItem.getUrl());
    }

    //显示设置列表
    private void showSettingGroup() {
        mBack.setVisibility(View.INVISIBLE);
        if (tvLeftChannelListLayout.getVisibility() == View.VISIBLE) {
            mHandler.removeCallbacks(mHideChannelListRun);
            mHandler.post(mHideChannelListRun);
        } else if (tvBottomLayout.getVisibility() == View.VISIBLE) {
            mHandler.removeCallbacks(mHideChannelInfoRun);
            mHandler.post(mHideChannelInfoRun);
        } else if (tvRightSettingLayout.getVisibility() == View.INVISIBLE) {
            if (!isCurrentLiveChannelValid()) return;
            //重新载入默认状态
            loadCurrentSourceList();
            liveSettingGroupAdapter.setNewData(liveSettingGroupList);
            selectSettingGroup(0, false);
            mSettingGroupView.scrollToPosition(0);
            mSettingItemView.scrollToPosition(currentLiveChannelItem.getSourceIndex());
            mHandler.postDelayed(mFocusAndShowSettingGroup, 200);
        } else {
            mBack.setVisibility(View.INVISIBLE);
            mHandler.removeCallbacks(mHideSettingLayoutRun);
            mHandler.post(mHideSettingLayoutRun);
        }
    }

    private final Runnable mFocusAndShowSettingGroup = new Runnable() {
        @Override
        public void run() {
            if (mSettingGroupView.isScrolling() || mSettingItemView.isScrolling() || mSettingGroupView.isComputingLayout() || mSettingItemView.isComputingLayout()) {
                mHandler.postDelayed(this, 100);
            } else {
                RecyclerView.ViewHolder holder = mSettingGroupView.findViewHolderForAdapterPosition(0);
                if (holder != null)
                    holder.itemView.requestFocus();
                tvRightSettingLayout.setVisibility(View.VISIBLE);
                tvRightSettingLayout.setAlpha(0.0f);
                tvRightSettingLayout.setTranslationX(tvRightSettingLayout.getWidth() / 2);
                tvRightSettingLayout.animate()
                        .translationX(0)
                        .alpha(1.0f)
                        .setDuration(250)
                        .setInterpolator(new DecelerateInterpolator())
                        .setListener(null);
                mHandler.removeCallbacks(mHideSettingLayoutRun);
                mHandler.postDelayed(mHideSettingLayoutRun, 6000);
                mHandler.postDelayed(mUpdateLayout, 255);   // Workaround Fix : SurfaceView
            }
        }
    };

    private final Runnable mHideSettingLayoutRun = new Runnable() {
        @Override
        public void run() {
            if (tvRightSettingLayout.getVisibility() == View.VISIBLE) {
                tvRightSettingLayout.animate()
                        .translationX(tvRightSettingLayout.getWidth() / 2)
                        .alpha(0.0f)
                        .setDuration(250)
                        .setInterpolator(new DecelerateInterpolator())
                        .setListener(new AnimatorListenerAdapter() {
                            @Override
                            public void onAnimationEnd(Animator animation) {
                                super.onAnimationEnd(animation);
                                tvRightSettingLayout.setVisibility(View.INVISIBLE);
                                tvRightSettingLayout.clearAnimation();
                                liveSettingGroupAdapter.setSelectedGroupIndex(-1);
                            }
                        });
            }
        }
    };

    private void initVideoView() {
        controller = new LiveController(this);
        controller.setListener(new LiveController.LiveControlListener() {
            @Override
            public boolean singleTap(MotionEvent e) {
                int fiveScreen = PlayerUtils.getScreenWidth(mContext, true) / 5;

                if (e.getX() > 0 && e.getX() < (fiveScreen * 2)) {
                    // left side <<<<<
                    showChannelList();
                } else if ((e.getX() > (fiveScreen * 2)) && (e.getX() < (fiveScreen * 3))) {
                    // middle screen
                    toggleChannelInfo();
                } else if (e.getX() > (fiveScreen * 3)) {
                    // right side >>>>>
                    showSettingGroup();
                }
                return true;
            }

            @Override
            public void longPress() {
                showSettingGroup();
            }

            @Override
            public void playStateChanged(int playState) {
                switch (playState) {
                    case VideoView.STATE_IDLE:
                    case VideoView.STATE_PAUSED:
                        break;
                    case VideoView.STATE_PREPARED:
                        // takagen99 : Retrieve Video Resolution & Retrieve Video Duration
                        if (mVideoView.getVideoSize().length >= 2) {
                            tv_size.setText(mVideoView.getVideoSize()[0] + " x " + mVideoView.getVideoSize()[1]);
                        }
                        // Show SeekBar if it's a VOD (with duration)
                        int duration = (int) mVideoView.getDuration();
                        if (duration > 0) {
                            isVOD = true;
                            llSeekBar.setVisibility(View.VISIBLE);
                            mSeekBar.setProgress(10);
                            mSeekBar.setMax(duration);
                            mSeekBar.setProgress(0);
                            mTotalTime.setText(stringForTimeVod(duration));
                        } else {
                            isVOD = false;
                            llSeekBar.setVisibility(View.GONE);
                        }
                        break;
                    case VideoView.STATE_BUFFERED:
                    case VideoView.STATE_PLAYING:
                        MonetizationManager.get().trackPlaySuccess("live");
                        mHandler.removeCallbacks(mConnectTimeoutChangeSourceRun);
                        channelSwitcher.onPlaying();
                        onLinePlaySuccess();
                        break;
                    case VideoView.STATE_ERROR:
                    case VideoView.STATE_PLAYBACK_COMPLETED:
                        MonetizationManager.get().trackPlayFail("live");
                        mHandler.removeCallbacks(mConnectTimeoutChangeSourceRun);
                        // 出错立即换线，不受超时设置影响
                        mHandler.post(mConnectTimeoutChangeSourceRun);
                        break;
                    case VideoView.STATE_PREPARING:
                        // 仅在真正开始一次新播放时重置去重标志；
                        // STATE_BUFFERING 属播放中途，不能视为新的开始
                        MonetizationManager.get().trackPlayStart("live");
                        // fall through
                    case VideoView.STATE_BUFFERING:
                        mHandler.removeCallbacks(mConnectTimeoutChangeSourceRun);
                        long timeout = LiveChannelSwitcher.timeoutMillis(Hawk.get(HawkConfig.LIVE_CONNECT_TIMEOUT, LiveChannelSwitcher.TIMEOUT_DEFAULT_INDEX));
                        // 「关」为 0：不做超时换线
                        if (timeout > 0) mHandler.postDelayed(mConnectTimeoutChangeSourceRun, timeout);
                        break;
                }
            }

            @Override
            public void changeSource(int direction) {
                if (direction > 0)
                    playNextSource();
                else
                    playPreSource();
            }
        });
        controller.setCanChangePosition(false);
        controller.setEnableInNormal(true);
        controller.setGestureEnabled(true);
        controller.setDoubleTapTogglePlayEnabled(false);
        mVideoView.setVideoController(controller);
        mVideoView.setProgressManager(null);
    }

    /** 当前线路失败（报错或超时）：换下一条线路；全部线路都失败则提示并停在当前频道 */
    private final Runnable mConnectTimeoutChangeSourceRun = new Runnable() {
        @Override
        public void run() {
            if (currentLiveChannelItem == null || mVideoView == null) return;
            if (channelSwitcher.onFailure() == LiveChannelSwitcher.FailAction.ALL_FAILED) {
                LOG.e("Live channel unavailable on all lines: " + currentLiveChannelItem.getChannelName());
                Toast.makeText(mContext, getString(R.string.live_channel_unavailable), Toast.LENGTH_LONG).show();
                return;
            }
            currentLiveChannelItem.nextSource();
            playChannel(currentChannelGroupIndex, currentLiveChannelIndex, true);
        }
    };

    private long playStartedAt = 0;
    private boolean playSucceeded = false;

    /** 起播成功：记住这条线路，并记录起播耗时供下次排序 */
    private void onLinePlaySuccess() {
        if (playSucceeded || currentLiveChannelItem == null || isSHIYI) return;
        playSucceeded = true;
        String url = currentLiveChannelItem.getUrl();
        LiveSourceMemory.rememberUrl(currentLiveChannelItem.getChannelKey(), url);
        if (playStartedAt > 0) {
            LiveSourceMemory.recordLatency(url, android.os.SystemClock.elapsedRealtime() - playStartedAt);
        }
    }

    private void initEpgListView() {
        mEpgInfoGridView.setHasFixedSize(true);
        mEpgInfoGridView.setLayoutManager(new V7LinearLayoutManager(this.mContext, 1, false));
        epgListAdapter = new LiveEpgAdapter();
        mEpgInfoGridView.setAdapter(epgListAdapter);

        mEpgInfoGridView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
                super.onScrollStateChanged(recyclerView, newState);
                mHandler.removeCallbacks(mHideChannelListRun);
                mHandler.postDelayed(mHideChannelListRun, 6000);
            }
        });
        //电视
        mEpgInfoGridView.setOnItemListener(new TvRecyclerView.OnItemListener() {
            @Override
            public void onItemPreSelected(TvRecyclerView parent, View itemView, int position) {
                epgListAdapter.setFocusedEpgIndex(-1);
            }

            @Override
            public void onItemSelected(TvRecyclerView parent, View itemView, int position) {
                mHandler.removeCallbacks(mHideChannelListRun);
                mHandler.postDelayed(mHideChannelListRun, 6000);
                epgListAdapter.setFocusedEpgIndex(position);
            }

            @Override
            public void onItemClick(TvRecyclerView parent, View itemView, int position) {
                onEpgItemClick(position);
            }
        });

        //手机/模拟器
        epgListAdapter.setOnItemClickListener(new BaseQuickAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(BaseQuickAdapter adapter, View view, int position) {
                onEpgItemClick(position);
            }
        });
    }

    /**
     * 点击节目单条目：正在播出的节目回到直播，已播完的节目进入回看，未开始的忽略。
     * 两种情况互斥（原实现两段 if 都会执行，点「直播中」也会变成回看）。
     */
    private void onEpgItemClick(int position) {
        if (currentLiveChannelItem == null || mVideoView == null) return;
        Epginfo selectedData = epgListAdapter.getItem(position);
        if (selectedData == null) return;
        Date date = selectedEpgDate();
        Date now = new Date();
        if (now.compareTo(selectedData.startdateTime) < 0) {
            return;
        }
        String liveUrl = currentLiveChannelItem.getUrl();
        if (now.compareTo(selectedData.enddateTime) <= 0) {
            epgListAdapter.setSelectedEpgIndex(position);
            mVideoView.release();
            isSHIYI = false;
            mVideoView.setUrl(liveUrl, setPlayHeaders(liveUrl));
            mVideoView.start();
            epgListAdapter.setShiyiSelection(-1, false, timeFormat.format(date));
            return;
        }
        if (!currentLiveChannelItem.getinclude_back()) {
            Toast.makeText(mContext, getString(R.string.live_catchup_unsupported), Toast.LENGTH_SHORT).show();
            return;
        }
        epgListAdapter.setSelectedEpgIndex(position);
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyyMMdd");
        dateFormat.setTimeZone(TimeZone.getTimeZone("GMT+8:00"));
        String targetDate = dateFormat.format(date);
        String shiyiStartdate = targetDate + selectedData.originStart.replace(":", "") + "30";
        String shiyiEnddate = targetDate + selectedData.originEnd.replace(":", "") + "30";
        mVideoView.release();
        shiyi_time = shiyiStartdate + "-" + shiyiEnddate;
        isSHIYI = true;
        mVideoView.setUrl(LiveCatchup.buildPlayseekUrl(liveUrl, shiyi_time), setPlayHeaders(liveUrl));
        mVideoView.start();
        epgListAdapter.setShiyiSelection(position, true, timeFormat.format(date));
        epgListAdapter.notifyDataSetChanged();
        mEpgInfoGridView.setSelectedPosition(position);
    }

    private void initEpgDateView() {
        mEpgDateGridView.setHasFixedSize(true);
        mEpgDateGridView.setLayoutManager(new V7LinearLayoutManager(this.mContext, 1, false));

        epgDateAdapter = new LiveEpgDateAdapter();
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(new Date());
//        SimpleDateFormat datePresentFormat = new SimpleDateFormat("dd-MMM", Locale.ENGLISH);
        SimpleDateFormat datePresentFormat = new SimpleDateFormat("EEEE", Locale.SIMPLIFIED_CHINESE);
        calendar.add(Calendar.DAY_OF_MONTH, -6);
        for (int i = 0; i < 9; i++) {
            Date dateIns = calendar.getTime();
            LiveEpgDate epgDate = new LiveEpgDate();
            epgDate.setIndex(i);

            // takagen99: Yesterday / Today / Tomorrow
            if (i == 5) {
                epgDate.setDatePresented("昨天");
            } else if (i == 6) {
                epgDate.setDatePresented("今天");
            } else if (i == 7) {
                epgDate.setDatePresented("明天");
            } else if (i == 8) {
                epgDate.setDatePresented("后天");
            } else {
                epgDate.setDatePresented(datePresentFormat.format(dateIns));
            }

            epgDate.setDateParamVal(dateIns);
            epgDateAdapter.addData(epgDate);
            calendar.add(Calendar.DAY_OF_MONTH, 1);
        }
        mEpgDateGridView.setAdapter(epgDateAdapter);
        mEpgDateGridView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
                super.onScrollStateChanged(recyclerView, newState);
                mHandler.removeCallbacks(mHideChannelListRun);
                mHandler.postDelayed(mHideChannelListRun, 6000);
            }
        });

        //电视
        mEpgDateGridView.setOnItemListener(new TvRecyclerView.OnItemListener() {
            @Override
            public void onItemPreSelected(TvRecyclerView parent, View itemView, int position) {
                epgDateAdapter.setFocusedIndex(-1);
            }

            @Override
            public void onItemSelected(TvRecyclerView parent, View itemView, int position) {
                mHandler.removeCallbacks(mHideChannelListRun);
                mHandler.postDelayed(mHideChannelListRun, 6000);
                epgDateAdapter.setFocusedIndex(position);
            }

            @Override
            public void onItemClick(TvRecyclerView parent, View itemView, int position) {
                mHandler.removeCallbacks(mHideChannelListRun);
                mHandler.postDelayed(mHideChannelListRun, 6000);
                epgDateAdapter.setSelectedIndex(position);
                getEpg(epgDateAdapter.getData().get(position).getDateParamVal());
            }
        });

        //手机/模拟器
        epgDateAdapter.setOnItemClickListener(new BaseQuickAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(BaseQuickAdapter adapter, View view, int position) {
                FastClickCheckUtil.check(view);
                mHandler.removeCallbacks(mHideChannelListRun);
                mHandler.postDelayed(mHideChannelListRun, 6000);
                epgDateAdapter.setSelectedIndex(position);
                getEpg(epgDateAdapter.getData().get(position).getDateParamVal());
            }
        });
        epgDateAdapter.setSelectedIndex(EPG_TODAY_INDEX);
    }

    private void initChannelGroupView() {
        mGroupGridView.setHasFixedSize(true);
        mGroupGridView.setLayoutManager(new V7LinearLayoutManager(this.mContext, 1, false));

        liveChannelGroupAdapter = new LiveChannelGroupAdapter();
        mGroupGridView.setAdapter(liveChannelGroupAdapter);
        mGroupGridView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
                super.onScrollStateChanged(recyclerView, newState);
                mHandler.removeCallbacks(mHideChannelListRun);
                mHandler.postDelayed(mHideChannelListRun, 6000);
            }
        });

        //电视
        mGroupGridView.setOnItemListener(new TvRecyclerView.OnItemListener() {
            @Override
            public void onItemPreSelected(TvRecyclerView parent, View itemView, int position) {
            }

            @Override
            public void onItemSelected(TvRecyclerView parent, View itemView, int position) {
                selectChannelGroup(position, true, -1);
            }

            @Override
            public void onItemClick(TvRecyclerView parent, View itemView, int position) {
                if (isNeedInputPassword(position)) {
                    showPasswordDialog(position, -1);
                }
            }
        });

        //手机/模拟器
        liveChannelGroupAdapter.setOnItemClickListener(new BaseQuickAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(BaseQuickAdapter adapter, View view, int position) {
                FastClickCheckUtil.check(view);
                selectChannelGroup(position, false, -1);
            }
        });
    }

    private void selectChannelGroup(int groupIndex, boolean focus, int liveChannelIndex) {
        if (focus) {
            liveChannelGroupAdapter.setFocusedGroupIndex(groupIndex);
            liveChannelItemAdapter.setFocusedChannelIndex(-1);
        }
        if ((groupIndex > -1 && groupIndex != liveChannelGroupAdapter.getSelectedGroupIndex()) || isNeedInputPassword(groupIndex)) {
            liveChannelGroupAdapter.setSelectedGroupIndex(groupIndex);
            if (isNeedInputPassword(groupIndex)) {
                showPasswordDialog(groupIndex, liveChannelIndex);
                return;
            }
            loadChannelGroupDataAndPlay(groupIndex, liveChannelIndex);
        }
        if (tvLeftChannelListLayout.getVisibility() == View.VISIBLE) {
            mHandler.removeCallbacks(mHideChannelListRun);
            mHandler.postDelayed(mHideChannelListRun, 6000);
        }
    }

    private void initLiveChannelView() {
        mChannelGridView.setHasFixedSize(true);
        mChannelGridView.setLayoutManager(new V7LinearLayoutManager(this.mContext, 1, false));

        liveChannelItemAdapter = new LiveChannelItemAdapter();
        mChannelGridView.setAdapter(liveChannelItemAdapter);
        mChannelGridView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
                super.onScrollStateChanged(recyclerView, newState);
                mHandler.removeCallbacks(mHideChannelListRun);
                mHandler.postDelayed(mHideChannelListRun, 6000);
            }
        });

        //电视
        mChannelGridView.setOnItemListener(new TvRecyclerView.OnItemListener() {
            @Override
            public void onItemPreSelected(TvRecyclerView parent, View itemView, int position) {
            }

            @Override
            public void onItemSelected(TvRecyclerView parent, View itemView, int position) {
                if (position < 0) return;
                liveChannelGroupAdapter.setFocusedGroupIndex(-1);
                liveChannelItemAdapter.setFocusedChannelIndex(position);
                mHandler.removeCallbacks(mHideChannelListRun);
                mHandler.postDelayed(mHideChannelListRun, 6000);
            }

            @Override
            public void onItemClick(TvRecyclerView parent, View itemView, int position) {
                clickLiveChannel(position);
            }
        });

        //手机/模拟器
        liveChannelItemAdapter.setOnItemClickListener(new BaseQuickAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(BaseQuickAdapter adapter, View view, int position) {
                FastClickCheckUtil.check(view);
                clickLiveChannel(position);
            }
        });
    }

    private void clickLiveChannel(int position) {
        liveChannelItemAdapter.setSelectedChannelIndex(position);

        // Set default as Today
        epgDateAdapter.setSelectedIndex(EPG_TODAY_INDEX);

        if (tvLeftChannelListLayout.getVisibility() == View.VISIBLE) {
            mHandler.removeCallbacks(mHideChannelListRun);
            mHandler.post(mHideChannelListRun);
//            mHandler.postDelayed(mHideChannelListRun, 500);
        }
        playChannel(liveChannelGroupAdapter.getSelectedGroupIndex(), position, false);
    }

    private void initSettingGroupView() {
        mSettingGroupView.setHasFixedSize(true);
        mSettingGroupView.setLayoutManager(new V7LinearLayoutManager(this.mContext, 1, false));

        liveSettingGroupAdapter = new LiveSettingGroupAdapter();
        mSettingGroupView.setAdapter(liveSettingGroupAdapter);
        mSettingGroupView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
                super.onScrollStateChanged(recyclerView, newState);
                mHandler.removeCallbacks(mHideSettingLayoutRun);
                mHandler.postDelayed(mHideSettingLayoutRun, 5000);
            }
        });

        //电视
        mSettingGroupView.setOnItemListener(new TvRecyclerView.OnItemListener() {
            @Override
            public void onItemPreSelected(TvRecyclerView parent, View itemView, int position) {
            }

            @Override
            public void onItemSelected(TvRecyclerView parent, View itemView, int position) {
                selectSettingGroup(position, true);
            }

            @Override
            public void onItemClick(TvRecyclerView parent, View itemView, int position) {
            }
        });

        //手机/模拟器
        liveSettingGroupAdapter.setOnItemClickListener(new BaseQuickAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(BaseQuickAdapter adapter, View view, int position) {
                FastClickCheckUtil.check(view);
                selectSettingGroup(position, false);
            }
        });
    }

    private void selectSettingGroup(int position, boolean focus) {
        if (!isCurrentLiveChannelValid()) return;
        if (focus) {
            liveSettingGroupAdapter.setFocusedGroupIndex(position);
            liveSettingItemAdapter.setFocusedItemIndex(-1);
        }
        if (position == liveSettingGroupAdapter.getSelectedGroupIndex() || position < -1)
            return;

        liveSettingGroupAdapter.setSelectedGroupIndex(position);
        liveSettingItemAdapter.setNewData(liveSettingGroupList.get(position).getLiveSettingItems());

        switch (position) {
            case SETTING_GROUP_LINE:
                liveSettingItemAdapter.selectItem(currentLiveChannelItem.getSourceIndex(), true, false);
                break;
            case SETTING_GROUP_SCALE:
                liveSettingItemAdapter.selectItem(livePlayerManager.getLivePlayerScale(), true, true);
                break;
            case SETTING_GROUP_DECODER:
                liveSettingItemAdapter.selectItem(livePlayerManager.getLivePlayerType(), true, true);
                break;
        }
        int scrollToPosition = liveSettingItemAdapter.getSelectedItemIndex();
        if (scrollToPosition < 0) scrollToPosition = 0;
        mSettingItemView.scrollToPosition(scrollToPosition);
        mHandler.removeCallbacks(mHideSettingLayoutRun);
        mHandler.postDelayed(mHideSettingLayoutRun, 5000);
    }

    private void initSettingItemView() {
        mSettingItemView.setHasFixedSize(true);
        mSettingItemView.setLayoutManager(new V7LinearLayoutManager(this.mContext, 1, false));

        liveSettingItemAdapter = new LiveSettingItemAdapter();
        mSettingItemView.setAdapter(liveSettingItemAdapter);
        mSettingItemView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
                super.onScrollStateChanged(recyclerView, newState);
                mHandler.removeCallbacks(mHideSettingLayoutRun);
                mHandler.postDelayed(mHideSettingLayoutRun, 5000);
            }
        });

        //电视
        mSettingItemView.setOnItemListener(new TvRecyclerView.OnItemListener() {
            @Override
            public void onItemPreSelected(TvRecyclerView parent, View itemView, int position) {
            }

            @Override
            public void onItemSelected(TvRecyclerView parent, View itemView, int position) {
                if (position < 0) return;
                liveSettingGroupAdapter.setFocusedGroupIndex(-1);
                liveSettingItemAdapter.setFocusedItemIndex(position);
                mHandler.removeCallbacks(mHideSettingLayoutRun);
                mHandler.postDelayed(mHideSettingLayoutRun, 5000);
            }

            @Override
            public void onItemClick(TvRecyclerView parent, View itemView, int position) {
                clickSettingItem(position);
            }
        });

        //手机/模拟器
        liveSettingItemAdapter.setOnItemClickListener(new BaseQuickAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(BaseQuickAdapter adapter, View view, int position) {
                FastClickCheckUtil.check(view);
                clickSettingItem(position);
            }
        });
    }

    // 直播内设置：分组与条目下标常量（与 initLiveSettingGroupList 的构建顺序一致）
    private static final int SETTING_GROUP_LINE = 0;
    private static final int SETTING_GROUP_SCALE = 1;
    private static final int SETTING_GROUP_DECODER = 2;
    private static final int SETTING_GROUP_TIMEOUT = 3;
    private static final int SETTING_GROUP_PREF = 4;
    private static final int SETTING_GROUP_EXIT = 5;
    private static final int PREF_SHOW_TIME = 0;
    private static final int PREF_SHOW_SPEED = 1;
    private static final int PREF_REVERSE = 2;
    private static final int PREF_CROSS_GROUP = 3;
    /** 起播超过该耗时的线路在线路列表里标注「较慢」 */
    private static final long SLOW_LINE_MS = 3000;

    private void clickSettingItem(int position) {
        int settingGroupIndex = liveSettingGroupAdapter.getSelectedGroupIndex();
        if (settingGroupIndex < SETTING_GROUP_PREF) {
            // 单选分组：重复点击当前项不处理
            if (position == liveSettingItemAdapter.getSelectedItemIndex())
                return;
            liveSettingItemAdapter.selectItem(position, true, true);
        }
        switch (settingGroupIndex) {
            case SETTING_GROUP_LINE:
                currentLiveChannelItem.setSourceIndex(position);
                rememberLiveSourceIndex(position);
                channelSwitcher.onManualSourceChange();
                playChannel(currentChannelGroupIndex, currentLiveChannelIndex, true);
                break;
            case SETTING_GROUP_SCALE:
                livePlayerManager.changeLivePlayerScale(mVideoView, position, currentLiveChannelItem.getChannelKey());
                break;
            case SETTING_GROUP_DECODER:
                mVideoView.release();
                livePlayerManager.changeLivePlayerType(mVideoView, position);
                channelSwitcher.onManualSourceChange();
                startPlayback();
                break;
            case SETTING_GROUP_TIMEOUT:
                Hawk.put(HawkConfig.LIVE_CONNECT_TIMEOUT, LiveChannelSwitcher.clampTimeoutIndex(position));
                break;
            case SETTING_GROUP_PREF:
                boolean select = false;
                switch (position) {
                    case PREF_SHOW_TIME:
                        select = !Hawk.get(HawkConfig.LIVE_SHOW_TIME, false);
                        Hawk.put(HawkConfig.LIVE_SHOW_TIME, select);
                        showTime();
                        break;
                    case PREF_SHOW_SPEED:
                        select = !Hawk.get(HawkConfig.LIVE_SHOW_NET_SPEED, false);
                        Hawk.put(HawkConfig.LIVE_SHOW_NET_SPEED, select);
                        showNetSpeed();
                        break;
                    case PREF_REVERSE:
                        select = !Hawk.get(HawkConfig.LIVE_CHANNEL_REVERSE, false);
                        Hawk.put(HawkConfig.LIVE_CHANNEL_REVERSE, select);
                        break;
                    case PREF_CROSS_GROUP:
                        select = !Hawk.get(HawkConfig.LIVE_CROSS_GROUP, false);
                        Hawk.put(HawkConfig.LIVE_CROSS_GROUP, select);
                        break;
                    default:
                        return;
                }
                liveSettingItemAdapter.selectItem(position, select, false);
                break;
            case SETTING_GROUP_EXIT:
                finish();
                return;
        }
        mHandler.removeCallbacks(mHideSettingLayoutRun);
        mHandler.postDelayed(mHideSettingLayoutRun, 5000);
    }

    private void initLiveChannelList() {
        // 频道列表在后台线程解析，加载完成后再初始化界面；
        // 「启动直接进直播」时列表可能还没准备好，不能直接判空退出
        showLoading();
        LiveRepository.get().load(new LiveRepository.Callback() {
            @Override
            public void onLoaded(List<LiveChannelGroup> groups) {
                if (activityDestroyed || isFinishing()) return;
                if (groups == null || groups.isEmpty()) {
                    Toast.makeText(App.getInstance(), getString(R.string.act_live_play_empty_channel), Toast.LENGTH_SHORT).show();
                    finish();
                    return;
                }
                liveChannelGroupList.clear();
                liveChannelGroupList.addAll(groups);
                showSuccess();
                initLiveState();
            }
        });
    }

    private void initLiveState() {
        int lastChannelGroupIndex = -1;
        int lastLiveChannelIndex = -1;
        Intent intent = getIntent();
        Bundle extras = intent == null ? null : intent.getExtras();
        if (extras != null && extras.containsKey("groupIndex")) {
            lastChannelGroupIndex = extras.getInt("groupIndex", 0);
            lastLiveChannelIndex = extras.getInt("channelIndex", 0);
        }
        if (lastChannelGroupIndex < 0 || lastChannelGroupIndex >= liveChannelGroupList.size()
                || lastLiveChannelIndex < 0
                || lastLiveChannelIndex >= liveChannelGroupList.get(lastChannelGroupIndex).getLiveChannels().size()) {
            Pair<Integer, Integer> lastChannel = JavaUtil.findLiveLastChannel(liveChannelGroupList);
            lastChannelGroupIndex = lastChannel.getFirst();
            lastLiveChannelIndex = lastChannel.getSecond();
        }

        livePlayerManager.init(mVideoView);
        showTime();
        showNetSpeed();
        tvLeftChannelListLayout.setVisibility(View.INVISIBLE);
        tvRightSettingLayout.setVisibility(View.INVISIBLE);

        liveChannelGroupAdapter.setNewData(liveChannelGroupList);
        selectChannelGroup(lastChannelGroupIndex, false, lastLiveChannelIndex);
    }

    private boolean isListOrSettingLayoutVisible() {
        return tvLeftChannelListLayout.getVisibility() == View.VISIBLE || tvRightSettingLayout.getVisibility() == View.VISIBLE;
    }

    private void initLiveSettingGroupList() {
        ArrayList<String> groupNames = new ArrayList<>(Arrays.asList(
                getString(R.string.live_setting_group_line),
                getString(R.string.live_setting_group_scale),
                getString(R.string.live_setting_group_decoder),
                getString(R.string.live_setting_group_timeout),
                getString(R.string.live_setting_group_pref),
                getString(R.string.live_setting_group_exit)));
        ArrayList<ArrayList<String>> itemsArrayList = new ArrayList<>();
        ArrayList<String> sourceItems = new ArrayList<>();
        // 顺序与 PlayerHelper 的画面比例取值 0..5 一致
        ArrayList<String> scaleItems = new ArrayList<>(Arrays.asList(
                getString(R.string.live_scale_default), getString(R.string.live_scale_16_9),
                getString(R.string.live_scale_4_3), getString(R.string.live_scale_fill),
                getString(R.string.live_scale_original), getString(R.string.live_scale_crop)));
        // 下标与 LivePlayerManager.DECODER_INDEX_* 一致；IJK 未打包，不再提供
        ArrayList<String> playerDecoderItems = new ArrayList<>(Arrays.asList(
                getString(R.string.live_decoder_system), getString(R.string.live_decoder_exo)));
        ArrayList<String> timeoutItems = new ArrayList<>();
        for (int seconds : LiveChannelSwitcher.TIMEOUT_SECONDS) {
            timeoutItems.add(seconds == 0 ? getString(R.string.live_timeout_off) : getString(R.string.live_timeout_seconds, seconds));
        }
        ArrayList<String> personalSettingItems = new ArrayList<>(Arrays.asList(
                getString(R.string.live_pref_show_time), getString(R.string.live_pref_show_speed),
                getString(R.string.live_pref_reverse), getString(R.string.live_pref_cross_group)));
        ArrayList<String> exitConfirm = new ArrayList<>(Arrays.asList(getString(R.string.live_exit_confirm)));
        itemsArrayList.add(sourceItems);
        itemsArrayList.add(scaleItems);
        itemsArrayList.add(playerDecoderItems);
        itemsArrayList.add(timeoutItems);
        itemsArrayList.add(personalSettingItems);
        itemsArrayList.add(exitConfirm);

        liveSettingGroupList.clear();
        for (int i = 0; i < groupNames.size(); i++) {
            LiveSettingGroup liveSettingGroup = new LiveSettingGroup();
            ArrayList<LiveSettingItem> liveSettingItemList = new ArrayList<>();
            liveSettingGroup.setGroupIndex(i);
            liveSettingGroup.setGroupName(groupNames.get(i));
            for (int j = 0; j < itemsArrayList.get(i).size(); j++) {
                LiveSettingItem liveSettingItem = new LiveSettingItem();
                liveSettingItem.setItemIndex(j);
                liveSettingItem.setItemName(itemsArrayList.get(i).get(j));
                liveSettingItemList.add(liveSettingItem);
            }
            liveSettingGroup.setLiveSettingItems(liveSettingItemList);
            liveSettingGroupList.add(liveSettingGroup);
        }
        int timeoutIndex = LiveChannelSwitcher.clampTimeoutIndex(Hawk.get(HawkConfig.LIVE_CONNECT_TIMEOUT, LiveChannelSwitcher.TIMEOUT_DEFAULT_INDEX));
        liveSettingGroupList.get(SETTING_GROUP_TIMEOUT).getLiveSettingItems().get(timeoutIndex).setItemSelected(true);
        ArrayList<LiveSettingItem> prefs = liveSettingGroupList.get(SETTING_GROUP_PREF).getLiveSettingItems();
        prefs.get(PREF_SHOW_TIME).setItemSelected(Hawk.get(HawkConfig.LIVE_SHOW_TIME, false));
        prefs.get(PREF_SHOW_SPEED).setItemSelected(Hawk.get(HawkConfig.LIVE_SHOW_NET_SPEED, false));
        prefs.get(PREF_REVERSE).setItemSelected(Hawk.get(HawkConfig.LIVE_CHANNEL_REVERSE, false));
        prefs.get(PREF_CROSS_GROUP).setItemSelected(Hawk.get(HawkConfig.LIVE_CROSS_GROUP, false));
    }

    /** 线路列表：显示线路名，并按本机记录标注「推荐」（首选或最快）/「较慢」 */
    private void loadCurrentSourceList() {
        ArrayList<String> currentSourceNames = currentLiveChannelItem.getChannelSourceNames();
        ArrayList<String> urls = currentLiveChannelItem.getChannelUrls();
        String preferred = LiveSourceMemory.preferredUrl(currentLiveChannelItem.getChannelKey());
        int fastest = -1;
        long fastestMs = Long.MAX_VALUE;
        for (int j = 0; j < urls.size(); j++) {
            long ms = LiveSourceMemory.latency(urls.get(j));
            if (ms >= 0 && ms < fastestMs) {
                fastestMs = ms;
                fastest = j;
            }
        }
        int recommended = preferred != null && urls.contains(preferred) ? urls.indexOf(preferred) : fastest;
        ArrayList<LiveSettingItem> liveSettingItemList = new ArrayList<>();
        for (int j = 0; j < currentSourceNames.size(); j++) {
            LiveSettingItem liveSettingItem = new LiveSettingItem();
            liveSettingItem.setItemIndex(j);
            String name = currentSourceNames.get(j);
            long ms = j < urls.size() ? LiveSourceMemory.latency(urls.get(j)) : -1;
            if (j == recommended && urls.size() > 1) {
                name = name + " · " + getString(R.string.live_line_tag_recommended);
            } else if (ms > SLOW_LINE_MS) {
                name = name + " · " + getString(R.string.live_line_tag_slow);
            }
            liveSettingItem.setItemName(name);
            liveSettingItemList.add(liveSettingItem);
        }
        liveSettingGroupList.get(SETTING_GROUP_LINE).setLiveSettingItems(liveSettingItemList);
    }

    void showTime() {
        mHandler.removeCallbacks(mUpdateTimeRun);
        if (Hawk.get(HawkConfig.LIVE_SHOW_TIME, false)) {
            mHandler.post(mUpdateTimeRun);
            tvTime.setVisibility(View.VISIBLE);
        } else {
            mHandler.removeCallbacks(mUpdateTimeRun);
            tvTime.setVisibility(View.GONE);
        }
    }

    private final Runnable mUpdateTimeRun = new Runnable() {
        @Override
        public void run() {
            Date day = new Date();
            SimpleDateFormat df = new SimpleDateFormat("HH:mm:ss");
            tvTime.setText(df.format(day));
            mHandler.postDelayed(this, 1000);
        }
    };

    private void showNetSpeed() {
        mHandler.removeCallbacks(mUpdateNetSpeedRun);
        if (Hawk.get(HawkConfig.LIVE_SHOW_NET_SPEED, false)) {
            lastRxBytes = -1;
            mHandler.post(mUpdateNetSpeedRun);
            tvNetSpeed.setVisibility(View.VISIBLE);
        } else {
            mHandler.removeCallbacks(mUpdateNetSpeedRun);
            tvNetSpeed.setVisibility(View.GONE);
        }
    }

    private long lastRxBytes = -1;
    private long lastRxTime = 0;

    /** 显示本应用的下行速度（TrafficStats 按 UID 统计），而不是整机流量 */
    private final Runnable mUpdateNetSpeedRun = new Runnable() {
        @Override
        public void run() {
            if (mVideoView == null) return;
            long now = android.os.SystemClock.elapsedRealtime();
            long rx = android.net.TrafficStats.getUidRxBytes(android.os.Process.myUid());
            if (rx >= 0 && lastRxBytes >= 0 && now > lastRxTime) {
                double speed = (rx - lastRxBytes) * 1000.0 / (now - lastRxTime);
                tvNetSpeed.setText(String.format(Locale.ROOT, "%.2fMB/s", speed / 1024.0 / 1024.0));
            }
            lastRxBytes = rx;
            lastRxTime = now;
            mHandler.postDelayed(this, 1000);
        }
    };

    private void showPasswordDialog(int groupIndex, int liveChannelIndex) {
        if (tvLeftChannelListLayout.getVisibility() == View.VISIBLE)
            mHandler.removeCallbacks(mHideChannelListRun);

        LivePasswordDialog dialog = new LivePasswordDialog(this);
        dialog.setOnListener(new LivePasswordDialog.OnListener() {
            @Override
            public void onChange(String password) {
                if (password.equals(liveChannelGroupList.get(groupIndex).getGroupPassword())) {
                    channelGroupPasswordConfirmed.add(groupIndex);
                    loadChannelGroupDataAndPlay(groupIndex, liveChannelIndex);
                } else {
                    Toast.makeText(App.getInstance(), getString(R.string.live_password_wrong), Toast.LENGTH_SHORT).show();
                }

                if (tvLeftChannelListLayout.getVisibility() == View.VISIBLE)
                    mHandler.postDelayed(mHideChannelListRun, 6000);
            }

            @Override
            public void onCancel() {
                if (tvLeftChannelListLayout.getVisibility() == View.VISIBLE) {
                    int groupIndex = liveChannelGroupAdapter.getSelectedGroupIndex();
                    liveChannelItemAdapter.setNewData(getLiveChannels(groupIndex));
                }
            }
        });
        dialog.show();
    }

    private void loadChannelGroupDataAndPlay(int groupIndex, int liveChannelIndex) {
        liveChannelItemAdapter.setNewData(getLiveChannels(groupIndex));
        if (groupIndex == currentChannelGroupIndex) {
            if (currentLiveChannelIndex > -1)
                mChannelGridView.scrollToPosition(currentLiveChannelIndex);
            liveChannelItemAdapter.setSelectedChannelIndex(currentLiveChannelIndex);
        } else {
            mChannelGridView.scrollToPosition(0);
            liveChannelItemAdapter.setSelectedChannelIndex(-1);
        }

        if (liveChannelIndex > -1) {
            clickLiveChannel(liveChannelIndex);
            mGroupGridView.scrollToPosition(groupIndex);
            mChannelGridView.scrollToPosition(liveChannelIndex);
            playChannel(groupIndex, liveChannelIndex, false);
        }
    }

    private boolean isNeedInputPassword(int groupIndex) {
        return !liveChannelGroupList.get(groupIndex).getGroupPassword().isEmpty()
                && !isPasswordConfirmed(groupIndex);
    }

    private boolean isPasswordConfirmed(int groupIndex) {
        if (Hawk.get(HawkConfig.LIVE_SKIP_PASSWORD, false)) {
            return true;
        } else {
            for (Integer confirmedNum : channelGroupPasswordConfirmed) {
                if (confirmedNum == groupIndex)
                    return true;
            }
            return false;
        }
    }

    private ArrayList<LiveChannelItem> getLiveChannels(int groupIndex) {
        if (!isNeedInputPassword(groupIndex)) {
            return liveChannelGroupList.get(groupIndex).getLiveChannels();
        } else {
            return new ArrayList<>();
        }
    }

    private Integer[] getNextChannel(int fromGroup, int fromChannel, int direction) {
        int[] next = LiveChannelSwitcher.nextChannel(new LiveChannelSwitcher.GroupAccess() {
            @Override
            public int groupCount() {
                return liveChannelGroupList.size();
            }

            @Override
            public int channelCount(int groupIndex) {
                return getLiveChannels(groupIndex).size();
            }

            @Override
            public boolean accessible(int groupIndex) {
                return !isNeedInputPassword(groupIndex);
            }
        }, fromGroup, fromChannel, direction, Hawk.get(HawkConfig.LIVE_CROSS_GROUP, false));
        if (next == null) {
            return new Integer[]{fromGroup, fromChannel};
        }
        return new Integer[]{next[0], next[1]};
    }

    private boolean isCurrentLiveChannelValid() {
        if (currentLiveChannelItem == null) {
            Toast.makeText(App.getInstance(), getString(R.string.live_select_channel_first), Toast.LENGTH_SHORT).show();
            return false;
        }
        return true;
    }
}
