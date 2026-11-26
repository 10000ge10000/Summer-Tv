package xyz.doikki.videoplayer.exo;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.net.TrafficStats;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;

import androidx.annotation.NonNull;
import androidx.media3.common.C;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.common.Tracks;
import androidx.media3.common.VideoSize;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.LoadControl;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;
import androidx.media3.exoplayer.trackselection.TrackSelectionArray;
import androidx.media3.ui.PlayerView;

import com.github.tvbox.osc.base.App;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.HawkUtils;
import com.github.tvbox.osc.util.PlaybackCompatProfile;
import com.github.tvbox.osc.util.PlayerHelper;
import com.orhanobut.hawk.Hawk;

import java.util.Locale;
import java.util.Map;

import xyz.doikki.videoplayer.player.AbstractPlayer;
import xyz.doikki.videoplayer.util.PlayerUtils;

public class ExoMediaPlayer extends AbstractPlayer implements Player.Listener {

    protected Context mAppContext;
    protected ExoPlayer mMediaPlayer;
    protected MediaSource mMediaSource;
    protected ExoMediaSourceHelper mMediaSourceHelper;
    protected ExoTrackNameProvider trackNameProvider;
    protected TrackSelectionArray mTrackSelections;
    private PlaybackParameters mSpeedPlaybackParameters;
    private boolean mIsPreparing;

    private LoadControl mLoadControl;
    private DefaultRenderersFactory mRenderersFactory;
    private DefaultTrackSelector mTrackSelector;

    private volatile int errorCode = 0;
    private volatile String lastErrorMessage = "";
    private volatile boolean videoFrameRendered = false;
    private volatile boolean hasVideoTrack = false;
    private String path;
    private Map<String, String> headers;
    private long lastTotalRxBytes = 0;
    private long lastTimeStamp = 0;

    private int retriedTimes = 0;
    /** 点播快速起播：缓冲 1 秒即开播（默认 2.5 秒）；直播保持默认，避免频繁卡顿 */
    private boolean fastStart = false;
    /** 兼容软解模式：优先使用内置 FFmpeg 扩展解码器，规避老芯片硬解花屏或黑屏 */
    private boolean preferSoftwareDecoder = false;

    public void setFastStart(boolean fastStart) {
        this.fastStart = fastStart;
    }

    public void setPreferSoftwareDecoder(boolean preferSoftwareDecoder) {
        this.preferSoftwareDecoder = preferSoftwareDecoder;
    }

    public int getLastErrorCode() {
        return errorCode;
    }

    public String getLastErrorMessage() {
        return lastErrorMessage;
    }

    public boolean isVideoFrameRendered() {
        return videoFrameRendered;
    }

    public boolean hasVideoTrack() {
        return hasVideoTrack;
    }

    public ExoMediaPlayer(Context context) {
        mAppContext = context.getApplicationContext();
        mMediaSourceHelper = ExoMediaSourceHelper.getInstance(context);
    }

    @SuppressLint("UnsafeOptInUsageError")
    @Override
    public void initPlayer() {
        if (mRenderersFactory == null) {
            mRenderersFactory = HawkUtils.createExoRendererActualValue(mAppContext);
        }
        // 硬解初始化失败时允许自动回退系统/FFmpeg 软解，避免老电视遇到高规格编码直接黑屏
        mRenderersFactory.setEnableDecoderFallback(true);
        int extMode = preferSoftwareDecoder
                ? DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
                : HawkUtils.getExoRendererModeActualValue();
        mRenderersFactory.setExtensionRendererMode(extMode);

        if (mTrackSelector == null) {
            mTrackSelector = new DefaultTrackSelector(mAppContext);
        }
        if (mLoadControl == null) {
            mLoadControl = fastStart
                    ? new DefaultLoadControl.Builder().setBufferDurationsMs(
                            DefaultLoadControl.DEFAULT_MIN_BUFFER_MS,
                            DefaultLoadControl.DEFAULT_MAX_BUFFER_MS,
                            1000,
                            DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS).build()
                    : new DefaultLoadControl();
        }
        // 关闭 Tunneling：它与 TextureView 冲突，且大量老旧电视芯片谎报支持导致黑屏卡死
        mTrackSelector.setParameters(mTrackSelector.getParameters().buildUpon().setPreferredTextLanguage(Locale.getDefault().getISO3Language()).setTunnelingEnabled(false));
        /*mMediaPlayer = new ExoPlayer.Builder(
                mAppContext,
                mRenderersFactory,
                mTrackSelector,
                new DefaultMediaSourceFactory(mAppContext),
                mLoadControl,
                DefaultBandwidthMeter.getSingletonInstance(mAppContext),
                new AnalyticsCollector(Clock.DEFAULT))
                .build();*/
        mMediaPlayer = new ExoPlayer.Builder(mAppContext)
                .setLoadControl(mLoadControl)
                .setRenderersFactory(mRenderersFactory)
                .setTrackSelector(mTrackSelector).build();

        setOptions();

        mMediaPlayer.addListener(this);
        mMediaSourceHelper.clearSocksProxy();
    }

    public DefaultTrackSelector getTrackSelector() {
        return mTrackSelector;
    }

    @Override
    public void setDataSource(String path, Map<String, String> headers) {
        this.path = path;
        this.headers = headers;
        mMediaSource = mMediaSourceHelper.getMediaSource(path, headers);
        errorCode = -1;
    }

    @Override
    public void setDataSource(AssetFileDescriptor fd) {
        //no support
    }

    @Override
    public void start() {
        if (mMediaPlayer == null)
            return;
        mMediaPlayer.setPlayWhenReady(true);
    }

    @Override
    public void pause() {
        if (mMediaPlayer == null)
            return;
        mMediaPlayer.setPlayWhenReady(false);
    }

    @Override
    public void stop() {
        if (mMediaPlayer == null)
            return;
        mMediaPlayer.stop();
    }

    @SuppressLint("UnsafeOptInUsageError")
    @Override
    public void prepareAsync() {
        if (mMediaPlayer == null)
            return;
        if (mMediaSource == null) return;
        if (mSpeedPlaybackParameters != null) {
            mMediaPlayer.setPlaybackParameters(mSpeedPlaybackParameters);
        }
        videoFrameRendered = false;
        hasVideoTrack = false;
        errorCode = 0;
        lastErrorMessage = "";
        mIsPreparing = true;
        mMediaPlayer.setMediaSource(mMediaSource);
        mMediaPlayer.prepare();
    }

    @Override
    public void reset() {
        if (mMediaPlayer != null) {
            mMediaPlayer.stop();
            mMediaPlayer.clearMediaItems();
            mMediaPlayer.setVideoSurface(null);
            mIsPreparing = false;
            videoFrameRendered = false;
            hasVideoTrack = false;
        }
    }

    @Override
    public boolean isPlaying() {
        if (mMediaPlayer == null)
            return false;
        int state = mMediaPlayer.getPlaybackState();
        switch (state) {
            case Player.STATE_BUFFERING:
            case Player.STATE_READY:
                return mMediaPlayer.getPlayWhenReady();
            case Player.STATE_IDLE:
            case Player.STATE_ENDED:
            default:
                return false;
        }
    }

    @Override
    public void seekTo(long time) {
        if (mMediaPlayer == null)
            return;
        mMediaPlayer.seekTo(time);
    }

    @Override
    public void release() {
        if (mMediaPlayer != null) {
            mMediaPlayer.removeListener(this);
            mMediaPlayer.release();
            mMediaPlayer = null;
        }
        lastTotalRxBytes = 0;
        lastTimeStamp = 0;
        mIsPreparing = false;
        mSpeedPlaybackParameters = null;
    }

    @Override
    public long getCurrentPosition() {
        if (mMediaPlayer == null)
            return 0;
        return mMediaPlayer.getCurrentPosition();
    }

    @Override
    public long getDuration() {
        if (mMediaPlayer == null)
            return 0;
        return mMediaPlayer.getDuration();
    }

    @Override
    public int getBufferedPercentage() {
        return mMediaPlayer == null ? 0 : mMediaPlayer.getBufferedPercentage();
    }

    public void setPlayerView(PlayerView view) {
        if (mMediaPlayer != null) {
            view.setPlayer(mMediaPlayer);
        }
    }

    @Override
    public void setSurface(Surface surface) {
        if (mMediaPlayer != null) {
            mMediaPlayer.setVideoSurface(surface);
        }
    }

    @Override
    public void setDisplay(SurfaceHolder holder) {
        if (holder == null)
            setSurface(null);
        else
            setSurface(holder.getSurface());
    }

    @Override
    public void setVolume(float leftVolume, float rightVolume) {
        if (mMediaPlayer != null)
            mMediaPlayer.setVolume((leftVolume + rightVolume) / 2);
    }

    @Override
    public void setLooping(boolean isLooping) {
        if (mMediaPlayer != null)
            mMediaPlayer.setRepeatMode(isLooping ? Player.REPEAT_MODE_ALL : Player.REPEAT_MODE_OFF);
    }

    @Override
    public void setOptions() {
        //准备好就开始播放
        mMediaPlayer.setPlayWhenReady(true);
    }

    @Override
    public float getSpeed() {
        if (mSpeedPlaybackParameters != null) {
            return mSpeedPlaybackParameters.speed;
        }
        return 1f;
    }

    @Override
    public void setSpeed(float speed) {
        PlaybackParameters playbackParameters = new PlaybackParameters(speed);
        mSpeedPlaybackParameters = playbackParameters;
        if (mMediaPlayer != null) {
            mMediaPlayer.setPlaybackParameters(playbackParameters);
        }
    }

    private boolean unsupported() {
        return TrafficStats.getUidRxBytes(App.getInstance().getApplicationInfo().uid) == TrafficStats.UNSUPPORTED;
    }

    @Override
    public long getTcpSpeed() {
        if (mAppContext == null || unsupported()) {
            return 0;
        }
        return PlayerUtils.getNetSpeed(mAppContext);
    }

    @Override
    public void onTracksChanged(Tracks tracks) {
        if (trackNameProvider == null)
            trackNameProvider = new ExoTrackNameProvider(mAppContext.getResources());
        if (tracks != null && tracks.containsType(C.TRACK_TYPE_VIDEO)) {
            hasVideoTrack = true;
        }
    }

    @Override
    public void onRenderedFirstFrame() {
        videoFrameRendered = true;
    }

    @Override
    public void onPlaybackStateChanged(int playbackState) {
        if (mPlayerEventListener == null) return;
        if (mIsPreparing) {
            if (playbackState == Player.STATE_READY) {
                mPlayerEventListener.onPrepared();
                mPlayerEventListener.onInfo(MEDIA_INFO_RENDERING_START, 0);
                mIsPreparing = false;
            }
            return;
        }
        switch (playbackState) {
            case Player.STATE_BUFFERING:
                mPlayerEventListener.onInfo(MEDIA_INFO_BUFFERING_START, getBufferedPercentage());
                break;
            case Player.STATE_READY:
                mPlayerEventListener.onInfo(MEDIA_INFO_BUFFERING_END, getBufferedPercentage());
                break;
            case Player.STATE_ENDED:
                mPlayerEventListener.onCompletion();
                break;
            case Player.STATE_IDLE:
                break;
        }
    }

    @Override
    public void onPlayerError(@NonNull PlaybackException error) {
        errorCode = error.errorCode;
        String rootMsg = PlayerHelper.getRootCauseMessage(error);
        lastErrorMessage = rootMsg != null ? rootMsg : String.valueOf(error.getMessage());
        Log.e("tag--", "Exo error=" + error.errorCode + " msg=" + lastErrorMessage);
        // 解码/渲染/音轨兼容错误不原地空转重试，立即上抛给 PlayFragment 触发内核降级
        if (PlaybackCompatProfile.isCompatibilityError(errorCode, lastErrorMessage)) {
            if (mPlayerEventListener != null) {
                mPlayerEventListener.onError(error.errorCode, lastErrorMessage);
            }
            return;
        }
        String proxyServer = Hawk.get(HawkConfig.PROXY_SERVER, "");
        if ("".equals(proxyServer)) {
            if (retriedTimes == 0) {
                retriedTimes = 1;
                setDataSource(path, headers);
                prepareAsync();
                start();
            } else {
                if (mPlayerEventListener != null) {
                    mPlayerEventListener.onError(error.errorCode, lastErrorMessage);
                }
            }
            return;
        }
        String[] proxyServers = proxyServer.split("\\s+|,|;|，");
        if (retriedTimes > proxyServers.length - 1) {
            if (mPlayerEventListener != null) {
                mPlayerEventListener.onError(error.errorCode, PlayerHelper.getRootCauseMessage(error));
            }
            return;
        }
        String[] ps = proxyServers[retriedTimes].split(":");
        if (ps.length != 2) {
            if (mPlayerEventListener != null) {
                mPlayerEventListener.onError(error.errorCode, PlayerHelper.getRootCauseMessage(error));
            }
            return;
        }
        try {
            mMediaSourceHelper.setSocksProxy(ps[0], Integer.parseInt(ps[1]));
            retriedTimes++;
            setDataSource(path, headers);
            prepareAsync();
            start();
        } catch (Exception e) {
            if (mPlayerEventListener != null) {
                mPlayerEventListener.onError(error.errorCode, PlayerHelper.getRootCauseMessage(error));
            }
            return;
        }
    }

    @Override
    public void onVideoSizeChanged(@NonNull VideoSize videoSize) {
        if (mPlayerEventListener != null) {
            mPlayerEventListener.onVideoSizeChanged(videoSize.width, videoSize.height);
            if (videoSize.unappliedRotationDegrees > 0) {
                mPlayerEventListener.onInfo(MEDIA_INFO_VIDEO_ROTATION_CHANGED, videoSize.unappliedRotationDegrees);
            }
        }
    }
}
