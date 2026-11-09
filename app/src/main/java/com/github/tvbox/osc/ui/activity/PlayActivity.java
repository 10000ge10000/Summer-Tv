package com.github.tvbox.osc.ui.activity;

import android.app.PendingIntent;
import android.app.PictureInPictureParams;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.util.Rational;
import android.view.KeyEvent;

import androidx.annotation.RequiresApi;
import androidx.fragment.app.Fragment;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.base.BaseActivity;
import com.github.tvbox.osc.player.MyVideoView;
import com.github.tvbox.osc.player.controller.VodController;
import com.github.tvbox.osc.ui.fragment.PlayFragment;
import com.github.tvbox.osc.util.HawkConfig;
import com.orhanobut.hawk.Hawk;

import java.util.ArrayList;
import java.util.List;

/**
 * 独立全屏点播页（UI 重构阶段 D）。
 * 播放逻辑全部在 {@link PlayFragment}，这里只负责宿主相关的部分：
 * 全屏承载 Fragment、隐藏系统栏、返回键 / 按键转发、画中画及其遥控广播。
 * Intent extras 与合并前相同（VodInfo、sourceKey 等），原样交给 Fragment。
 */
public class PlayActivity extends BaseActivity {
    public static final String BROADCAST_ACTION = "VOD_CONTROL";
    public static final int BROADCAST_ACTION_PREV = 0;
    public static final int BROADCAST_ACTION_PLAYPAUSE = 1;
    public static final int BROADCAST_ACTION_NEXT = 2;

    private PlayFragment playFragment;
    private BroadcastReceiver pipActionReceiver;
    // takagen99 : Use onStopCalled to track close activity
    private boolean onStopCalled;

    @Override
    protected int getLayoutResID() {
        return R.layout.activity_play_host;
    }

    @Override
    protected void init() {
        // takagen99 : Hide only when video playing
        hideSystemUI(false);
        Fragment existing = getSupportFragmentManager().findFragmentById(R.id.playContainer);
        if (existing instanceof PlayFragment) {
            playFragment = (PlayFragment) existing;
        } else {
            playFragment = PlayFragment.newStandalone(getIntent() == null ? null : getIntent().getExtras());
            getSupportFragmentManager().beginTransaction().add(R.id.playContainer, playFragment).commit();
        }
    }

    /** Fragment 初始化完成（控制器已创建）后才转发按键，避免启动瞬间按键导致空指针 */
    private VodController controller() {
        return playFragment == null ? null : playFragment.getVodController();
    }

    @Override
    public void onBackPressed() {
        if (controller() != null && playFragment.onBackPressed()) {
            return;
        }
        super.onBackPressed();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event != null && controller() != null && playFragment.dispatchKeyEvent(event)) {
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    protected void onResume() {
        super.onResume();
        onStopCalled = false;
    }

    @Override
    protected void onStop() {
        super.onStop();
        onStopCalled = true;
    }

    @Override
    public void onUserLeaveHint() {
        MyVideoView player = playFragment == null ? null : playFragment.getPlayer();
        if (player != null && controller() != null && supportsPiPMode() && !playFragment.extPlay
                && Hawk.get(HawkConfig.BACKGROUND_PLAY_TYPE, 0) == 2) {
            // Calculate Video Resolution
            int vWidth = player.getVideoSize()[0];
            int vHeight = player.getVideoSize()[1];
            Rational ratio;
            if (vWidth != 0) {
                if ((((double) vWidth) / ((double) vHeight)) > 2.39) {
                    vHeight = (int) (((double) vWidth) / 2.35);
                }
                ratio = new Rational(vWidth, vHeight);
            } else {
                ratio = new Rational(16, 9);
            }

            List<android.app.RemoteAction> actions = new ArrayList<>();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                actions.add(generateRemoteAction(android.R.drawable.ic_media_previous, BROADCAST_ACTION_PREV, "Prev", "Play Previous"));
                actions.add(generateRemoteAction(android.R.drawable.ic_media_play, BROADCAST_ACTION_PLAYPAUSE, "Play/Pause", "Play or Pause"));
                actions.add(generateRemoteAction(android.R.drawable.ic_media_next, BROADCAST_ACTION_NEXT, "Next", "Play Next"));
            }
            PictureInPictureParams params = new PictureInPictureParams.Builder()
                    .setAspectRatio(ratio)
                    .setActions(actions).build();
            enterPictureInPictureMode(params);

            controller().hideBottom();
            player.postDelayed(() -> {
                if (!player.isPlaying() && controller() != null) {
                    controller().togglePlay();
                }
            }, 400);
        }
        super.onUserLeaveHint();
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    private android.app.RemoteAction generateRemoteAction(int iconResId, int actionCode, String title, String desc) {
        final PendingIntent intent = PendingIntent.getBroadcast(
                PlayActivity.this,
                actionCode,
                new Intent(BROADCAST_ACTION).setPackage(getPackageName()).putExtra("action", actionCode),
                // targetSdk 31+ 必须显式声明可变性，否则进入画中画时抛 IllegalArgumentException
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        final Icon icon = Icon.createWithResource(PlayActivity.this, iconResId);
        return (new android.app.RemoteAction(icon, title, desc, intent));
    }

    // takagen99 : PIP fix to close video when close window
    @Override
    public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode);
        if (supportsPiPMode() && isInPictureInPictureMode) {
            unregisterPipReceiver();
            pipActionReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (intent == null || !BROADCAST_ACTION.equals(intent.getAction()) || controller() == null) {
                        return;
                    }
                    int currentStatus = intent.getIntExtra("action", 1);
                    if (currentStatus == BROADCAST_ACTION_PREV) {
                        playFragment.playPrevious();
                    } else if (currentStatus == BROADCAST_ACTION_PLAYPAUSE) {
                        controller().togglePlay();
                    } else if (currentStatus == BROADCAST_ACTION_NEXT) {
                        playFragment.playNext(false);
                    }
                }
            };
            registerInternalReceiver(pipActionReceiver, new IntentFilter(BROADCAST_ACTION));
        } else {
            // Closed playback
            if (onStopCalled && playFragment != null && playFragment.getPlayer() != null) {
                playFragment.getPlayer().release();
            }
            unregisterPipReceiver();
        }
    }

    private void registerInternalReceiver(BroadcastReceiver receiver, IntentFilter filter) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(receiver, filter);
        }
    }

    private void unregisterPipReceiver() {
        if (pipActionReceiver != null) {
            try {
                unregisterReceiver(pipActionReceiver);
            } catch (IllegalArgumentException ignored) {
                // 已经注销过
            }
            pipActionReceiver = null;
        }
    }

    @Override
    protected void onDestroy() {
        unregisterPipReceiver();
        super.onDestroy();
    }
}
