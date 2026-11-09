package com.github.tvbox.osc.commercial;

import android.app.Activity;
import android.content.Context;
import android.view.ViewGroup;

import com.github.tvbox.osc.util.HawkConfig;
import com.orhanobut.hawk.Hawk;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class MonetizationManager {
    private static final String DEVICE_ID_KEY = "commercial_device_id";
    private static final MonetizationManager INSTANCE = new MonetizationManager();

    private final CommercialConfigRepository configRepository = new CommercialConfigRepository();
    private final AdProvider adProvider = new RemoteAdProvider();
    private final AnalyticsProvider analyticsProvider = new QueuedAnalyticsProvider();

    private MonetizationManager() {
    }

    public static MonetizationManager get() {
        return INSTANCE;
    }

    /**
     * 用户是否允许匿名统计。默认 true（第四轮按用户要求默认开启）：关于页有一行说明和开关，
     * 用户关闭后写入 false，此后不再采集。
     *
     * <p>这是采集的硬前提，服务端下发的 analyticsEnabled 无法绕过它：
     * 服务端开关决定"是否需要"，用户同意决定"是否允许"。
     */
    public static boolean analyticsAllowed() {
        return Hawk.get(HawkConfig.ANALYTICS_CONSENT, true);
    }

    /** 设置页切换同意状态。撤回时立即清空待发队列。 */
    public void setAnalyticsConsent(boolean granted) {
        Hawk.put(HawkConfig.ANALYTICS_CONSENT, granted);
        if (!granted && analyticsProvider instanceof QueuedAnalyticsProvider) {
            ((QueuedAnalyticsProvider) analyticsProvider).clearQueue();
        }
    }

    public void init(Context context) {
        CommercialHttp.initialize(context);
        getDeviceId();
        configRepository.loadCachedConfig(context);
        configRepository.refreshIfNeeded(context, true);
        track("app_open", null);
    }

    public void refreshIfNeeded(Context context, boolean force) {
        configRepository.refreshIfNeeded(context, force);
    }

    public CommercialConfig getConfig() {
        return configRepository.getCurrentConfig();
    }

    public String getDeviceId() {
        String id = Hawk.get(DEVICE_ID_KEY, "");
        if (id == null || id.isEmpty()) {
            id = UUID.randomUUID().toString();
            Hawk.put(DEVICE_ID_KEY, id);
        }
        return id;
    }

    public void showAd(Activity activity, ViewGroup parent, AdPlacement placement) {
        trackAdRequest(placement);
        adProvider.showPlacement(activity, parent, placement);
    }

    /**
     * 播放结果埋点。服务端日报统计 play_success / play_fail 计算播放成功率，
     * 但客户端此前从未产生这两个事件，该指标一直恒为 0。
     *
     * <p>每次播放只上报一个结果：STATE_PLAYING 会在暂停恢复、缓冲结束等场景重复触发，
     * 不去重会让成功数虚高。
     */
    private volatile boolean playOutcomeReported = true;

    public void trackPlayStart(String scene) {
        playOutcomeReported = false;
        Map<String, String> params = new HashMap<>();
        params.put("scene", scene);
        track("play_start", params);
    }

    public void trackPlaySuccess(String scene) {
        if (playOutcomeReported) return;
        playOutcomeReported = true;
        Map<String, String> params = new HashMap<>();
        params.put("scene", scene);
        track("play_success", params);
    }

    public void trackPlayFail(String scene) {
        if (playOutcomeReported) return;
        playOutcomeReported = true;
        Map<String, String> params = new HashMap<>();
        params.put("scene", scene);
        track("play_fail", params);
    }

    public void trackScreen(String screenName) {
        Map<String, String> params = new HashMap<>();
        params.put("screen", screenName);
        track("screen_view", params);
    }

    public void trackAdRequest(AdPlacement placement) {
        Map<String, String> params = new HashMap<>();
        params.put("placement", placement.name());
        track("ad_request", params);
    }

    public void trackAdShow(AdPlacement placement) {
        Map<String, String> params = new HashMap<>();
        params.put("placement", placement.name());
        track("ad_show", params);
    }

    public void trackAdClick(AdPlacement placement) {
        Map<String, String> params = new HashMap<>();
        params.put("placement", placement.name());
        track("ad_click", params);
    }

    public void track(String eventName, Map<String, String> params) {
        analyticsProvider.track(eventName, params);
    }

    public void flushAnalytics() {
        analyticsProvider.flush();
    }
}
