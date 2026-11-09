package com.github.tvbox.osc.base;

import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;

import androidx.core.os.HandlerCompat;
import androidx.multidex.MultiDexApplication;

import com.github.catvod.crawler.JarLoader;
import com.github.catvod.crawler.JsLoader;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.live.LiveMigration;
import com.github.tvbox.osc.callback.EmptyCallback;
import com.github.tvbox.osc.callback.LoadingCallback;
import com.github.tvbox.osc.callback.PosterSkeletonCallback;
import com.github.tvbox.osc.commercial.MonetizationManager;
import com.github.tvbox.osc.data.AppDataManager;
import com.github.tvbox.osc.server.ControlManager;
import com.github.tvbox.osc.util.CrashHandler;
import com.github.tvbox.osc.util.EpgUtil;
import com.github.tvbox.osc.util.FileUtils;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.LocaleHelper;
import com.github.tvbox.osc.util.LOG;
import com.github.tvbox.osc.util.OkGoHelper;
import com.github.tvbox.osc.util.PlayerHelper;
import com.github.tvbox.osc.util.SubtitleHelper;
import com.hjq.permissions.XXPermissions;
import com.kingja.loadsir.core.LoadSir;
import com.orhanobut.hawk.Hawk;
import com.p2p.P2PClass;
import com.yanzhenjie.andserver.AndServer;
import com.yanzhenjie.andserver.Server;

import java.io.File;
import java.util.concurrent.TimeUnit;

import io.github.inflationx.calligraphy3.CalligraphyConfig;
import io.github.inflationx.calligraphy3.CalligraphyInterceptor;
import io.github.inflationx.viewpump.ViewPump;
import me.jessyan.autosize.AutoSizeConfig;
import me.jessyan.autosize.unit.Subunits;

/**
 * @author pj567
 * @date :2020/12/17
 * @description:
 */
public class App extends MultiDexApplication {
    private static final String DEFAULT_API_URL = "https://tv2.910501.xyz/api/tvbox/config?format=json&mode=fast";
    /**
     * 早期版本把第三方订阅地址写进了默认直播源，其 URL 携带鉴权参数。
     * 按 host 匹配清除，不保留任何完整地址：旧版涉及两个账号且存在逐频道地址，
     * 精确匹配单条 URL 会大量漏网。
     */
    public static final String REVOKED_LIVE_HOST = "live.ottiptv.cc";
    public static final String DEFAULT_LIVE_URL = "";
    public static final String DEFAULT_HTTP_UA = "okHttp/Mod-1.5.0.0";
    private static final String DEFAULT_HOME_SOURCE_KEY = "";
    private static final int DEFAULT_VOD_PLAYER_TYPE = 2; // Exo
    private static final int DEFAULT_LIVE_PLAYER_TYPE = 0; // System
    private static final String PLAYER_DEFAULT_SYSTEM_MIGRATED = "player_default_system_migrated";
    private static final String VOD_DEFAULT_EXO_MIGRATED = "vod_default_exo_migrated";
    private static final String REVOKED_LIVE_CACHE_PURGED = "revoked_live_cache_purged";
    private static final String UNAVAILABLE_PLAYER_MIGRATED = "unavailable_player_migrated";
    private static final String SIMPLIFIED_SETTINGS_MIGRATED = "simplified_settings_v3_migrated";
    private static App instance;
    private static P2PClass p;
    public static String burl;
    private static String dashData;
    public static ViewPump viewPump = null;
    private static Server server = null;
    private final Handler handler;

    public App() {
        instance = this;
        handler = HandlerCompat.createAsync(Looper.getMainLooper());
    }

    @Override
    public void onCreate() {
        super.onCreate();
        CrashHandler.install(this);
        SubtitleHelper.initSubtitleColor(this);
        initParams();
        // takagen99 : Initialize Locale
        initLocale();
        // OKGo
        OkGoHelper.init();
        MonetizationManager.get().init(this);
        ApiConfig.get().refreshRemoteLiveCache();
        // 闭关检查模式
        XXPermissions.setCheckMode(false);
        // Get EPG Info
        EpgUtil.init();
        // 初始化Web服务器
        ControlManager.init(this);
        //初始化数据库
        AppDataManager.init();
        LoadSir.beginBuilder()
                .addCallback(new EmptyCallback())
                .addCallback(new LoadingCallback())
                .addCallback(new PosterSkeletonCallback())
                .commit();
        AutoSizeConfig.getInstance().setCustomFragment(true).getUnitsManager()
                .setSupportDP(false)
                .setSupportSP(false)
                .setSupportSubunits(Subunits.MM);
        // 修复 Android 14+：冷启动时 ScreenUtils 可能获取到竖屏宽度，导致 xdpi 计算错误、UI 变小
        int screenWidth = AutoSizeConfig.getInstance().getScreenWidth();
        int screenHeight = AutoSizeConfig.getInstance().getScreenHeight();
        if (screenWidth < screenHeight) {
            AutoSizeConfig.getInstance().setScreenWidth(screenHeight);
            AutoSizeConfig.getInstance().setScreenHeight(screenWidth);
        }
        PlayerHelper.init();

        // Delete Cache
        /*File dir = getCacheDir();
        FileUtils.recursiveDelete(dir);
        dir = getExternalCacheDir();
        FileUtils.recursiveDelete(dir);*/

        FileUtils.cleanPlayerCache();

        // Public build keeps startup native-free to avoid Android 16 KB page-size compatibility dialogs.

        // add font support, my tv embed font not include emoji
        String extStorageDir = Environment.getExternalStorageDirectory().getAbsolutePath();
        File fontFile = new File(extStorageDir + "/tvbox.ttf");
        if (fontFile.exists()) {
            viewPump = ViewPump.builder()
                    .addInterceptor(new CalligraphyInterceptor(
                            new CalligraphyConfig.Builder()
                                    .setDefaultFontPath(fontFile.getAbsolutePath())
                                    .setFontAttrId(R.attr.fontPath)
                                    .build()))
                    .build();
        }
    }

    public static P2PClass getp2p() {
        try {
            if (p == null) {
                p = new P2PClass(FileUtils.getExternalCachePath());
            }
            return p;
        } catch (Exception e) {
            LOG.e(e.toString());
            return null;
        }
    }


    private void initParams() {
        // Hawk
        Hawk.init(this).build();
        // 直播数据迁移（线路记忆按 URL、超时 / 画面比例 / 解码映射，清掉已隐藏的自定义直播地址）。
        // 必须在下面读取旧 LIVE_URL 之前执行，否则旧地址会被恢复回去。
        LiveMigration.run();
        boolean hadDebugOpen = Hawk.contains(HawkConfig.DEBUG_OPEN);
        boolean oldDebugOpen = Hawk.get(HawkConfig.DEBUG_OPEN, false);
        boolean hadApiUrl = Hawk.contains(HawkConfig.API_URL);
        String oldApiUrl = Hawk.get(HawkConfig.API_URL, "");
        if (isRevokedLiveUrl(oldApiUrl)) {
            oldApiUrl = "";
        }
        boolean hadLiveUrl = Hawk.contains(HawkConfig.LIVE_URL);
        String oldLiveUrl = Hawk.get(HawkConfig.LIVE_URL, "");
        if (isRevokedLiveUrl(oldLiveUrl)) {
            oldLiveUrl = "";
        }
        boolean hadHomeApi = Hawk.contains(HawkConfig.HOME_API);
        String oldHomeApi = Hawk.get(HawkConfig.HOME_API, "");
        if ("ffzy".equals(oldHomeApi) && !Hawk.get("home_api_movie_default_migrated", false)) {
            oldHomeApi = "";
            Hawk.put("home_api_movie_default_migrated", true);
        }
        boolean hadHomeRec = Hawk.contains(HawkConfig.HOME_REC);
        int oldHomeRec = Hawk.get(HawkConfig.HOME_REC, 0);
        boolean hadPlayType = Hawk.contains(HawkConfig.PLAY_TYPE);
        int oldPlayType = Hawk.get(HawkConfig.PLAY_TYPE, DEFAULT_VOD_PLAYER_TYPE);
        boolean hadLivePlayType = Hawk.contains(HawkConfig.LIVE_PLAYER_TYPE);
        int oldLivePlayType = Hawk.get(HawkConfig.LIVE_PLAYER_TYPE, DEFAULT_LIVE_PLAYER_TYPE);
        if (!Hawk.get(PLAYER_DEFAULT_SYSTEM_MIGRATED, false)) {
            if (hadPlayType && oldPlayType == 2) oldPlayType = DEFAULT_LIVE_PLAYER_TYPE;
            if (hadLivePlayType && oldLivePlayType == 2) oldLivePlayType = DEFAULT_LIVE_PLAYER_TYPE;
            Hawk.put(PLAYER_DEFAULT_SYSTEM_MIGRATED, true);
        }
        if (!Hawk.get(VOD_DEFAULT_EXO_MIGRATED, false)) {
            if (!hadPlayType || oldPlayType == DEFAULT_LIVE_PLAYER_TYPE) {
                oldPlayType = DEFAULT_VOD_PLAYER_TYPE;
            }
            Hawk.put(VOD_DEFAULT_EXO_MIGRATED, true);
        }
        Hawk.put(HawkConfig.DEBUG_OPEN, false);
        Hawk.put(HawkConfig.API_URL, DEFAULT_API_URL);
        Hawk.put(HawkConfig.LIVE_URL, DEFAULT_LIVE_URL);
        Hawk.put(HawkConfig.HOME_API, DEFAULT_HOME_SOURCE_KEY);

        // 首页选项
        putDefault(HawkConfig.HOME_SHOW_SOURCE, false);      //数据源显示: true=开启, false=关闭
        putDefault(HawkConfig.HOME_SEARCH_POSITION, true);   //按钮位置-搜索: true=上方, false=下方
        putDefault(HawkConfig.HOME_MENU_POSITION, true);     //按钮位置-设置: true=上方, false=下方
        Hawk.put(HawkConfig.HOME_REC, 1);                    //推荐: 0=豆瓣热播, 1=站点推荐, 2=观看历史
        putDefault(HawkConfig.HOME_NUM, 1);                  //历史条数: 0=20条, 1=40条, 2=60条, 3=80条, 4=100条
        // 播放器选项
        putDefault(HawkConfig.SHOW_PREVIEW, false);          //窗口预览: true=开启, false=关闭
        putDefault(HawkConfig.PLAY_SCALE, 0);                //画面缩放: 0=默认, 1=16:9, 2=4:3, 3=填充, 4=原始, 5=裁剪
        putDefault(HawkConfig.BACKGROUND_PLAY_TYPE, 0);      //后台：0=关闭, 1=开启, 2=画中画
        Hawk.put(HawkConfig.PLAY_TYPE, DEFAULT_VOD_PLAYER_TYPE);              //点播: 0=系统, 1=IJK, 2=Exo
        Hawk.put(HawkConfig.LIVE_PLAYER_TYPE, DEFAULT_LIVE_PLAYER_TYPE);       //直播: 0=系统, 1=IJK, 2=Exo
        if (hadDebugOpen) Hawk.put(HawkConfig.DEBUG_OPEN, oldDebugOpen);
        if (hadApiUrl) Hawk.put(HawkConfig.API_URL, oldApiUrl);
        if (hadLiveUrl) Hawk.put(HawkConfig.LIVE_URL, oldLiveUrl);
        if (hadHomeApi) Hawk.put(HawkConfig.HOME_API, oldHomeApi);
        if (hadHomeRec) Hawk.put(HawkConfig.HOME_REC, oldHomeRec);
        else Hawk.put(HawkConfig.HOME_REC, 0);
        if (hadPlayType) Hawk.put(HawkConfig.PLAY_TYPE, oldPlayType);
        if (hadLivePlayType) Hawk.put(HawkConfig.LIVE_PLAYER_TYPE, oldLivePlayType);
        putDefault(HawkConfig.HOME_REC_STYLE, true);
        putDefault(HawkConfig.IJK_CODEC, "硬解码");           //IJK解码: 软解码, 硬解码
        // 系统选项
        putDefault(HawkConfig.HOME_LOCALE, 0);               //语言: 0=中文, 1=英文
        putDefault(HawkConfig.THEME_SELECT, 0);              //主题: 0=夏日(默认), 1=哆啦, 2=百事, 3=鸣人, 4=小黄, 5=八神, 6=樱花, 7=奈飞
        putDefault(HawkConfig.SEARCH_VIEW, 1);               //搜索展示: 0=文字列表, 1=缩略图
        putDefault(HawkConfig.PARSE_WEBVIEW, true);          //嗅探Webview: true=系统自带, false=XWalkView
        putDefault(HawkConfig.DOH_URL, 0);                   //安全DNS: 0=关闭, 1=腾讯, 2=阿里, 3=360, 4=Google, 5=AdGuard, 6=Quad9

        migrateUnavailablePlayerType();
        migrateSimplifiedSettings();
        purgeRevokedLiveCache();
    }

    /**
     * 第三轮设置精简的一次性迁移。必须在上面的旧值恢复之后执行，否则会被旧值覆盖。
     *
     * <p>主题、搜索展示、配置地址、直播地址的入口都已隐藏，老用户留着的非默认值无处可改，
     * 统一回到默认；首页固定网格样式（单行样式无法全屏展示今日精选）。
     */
    private void migrateSimplifiedSettings() {
        if (Hawk.get(SIMPLIFIED_SETTINGS_MIGRATED, false)) return;
        Hawk.put(HawkConfig.THEME_SELECT, 0);
        Hawk.put(HawkConfig.SEARCH_VIEW, 1);
        Hawk.put(HawkConfig.HOME_REC_STYLE, true);
        // 窗口预览入口已隐藏；预览模式下没有失败自动换线 / 换源，统一关掉
        Hawk.put(HawkConfig.SHOW_PREVIEW, false);
        Hawk.put(HawkConfig.API_URL, DEFAULT_API_URL);
        Hawk.put(HawkConfig.LIVE_URL, DEFAULT_LIVE_URL);
        Hawk.delete(HawkConfig.LIVE_HISTORY);
        Hawk.put(SIMPLIFIED_SETTINGS_MIGRATED, true);
    }

    /**
     * IJK(1) 与阿里云(3) 的 native 库已不在 APK 内，把选中它们的老用户迁到 Exo。
     *
     * <p>不迁移的话，升级后设置页仍显示 IJK，播放走 PlayerHelper 的兜底改用 Exo，
     * 显示与实际不符。
     */
    private void migrateUnavailablePlayerType() {
        if (Hawk.get(UNAVAILABLE_PLAYER_MIGRATED, false)) return;
        int vod = Hawk.get(HawkConfig.PLAY_TYPE, DEFAULT_VOD_PLAYER_TYPE);
        if (vod == 1 || vod == 3) Hawk.put(HawkConfig.PLAY_TYPE, DEFAULT_VOD_PLAYER_TYPE);
        int live = Hawk.get(HawkConfig.LIVE_PLAYER_TYPE, DEFAULT_LIVE_PLAYER_TYPE);
        if (live == 1 || live == 3) Hawk.put(HawkConfig.LIVE_PLAYER_TYPE, DEFAULT_LIVE_PLAYER_TYPE);
        Hawk.put(UNAVAILABLE_PLAYER_MIGRATED, true);
    }

    private boolean isRevokedLiveUrl(String url) {
        if (url == null || url.isEmpty()) return false;
        try {
            return REVOKED_LIVE_HOST.equalsIgnoreCase(Uri.parse(url).getHost());
        } catch (Throwable th) {
            return false;
        }
    }

    /**
     * 旧版可能已把带鉴权参数的订阅内容缓存到本机。清一次缓存文件强制重新拉取，
     * 只执行一次，避免每次启动都丢弃正常缓存。
     */
    private void purgeRevokedLiveCache() {
        if (Hawk.get(REVOKED_LIVE_CACHE_PURGED, false)) return;
        try {
            File cache = new File(getFilesDir(), "user_result.m3u");
            if (cache.isFile()) {
                LOG.i("Purge legacy live cache: " + cache.delete());
            }
        } catch (Throwable th) {
            LOG.e("Purge legacy live cache failed: " + th.getMessage());
        }
        Hawk.put(REVOKED_LIVE_CACHE_PURGED, true);
    }

    private void initLocale() {
        if (Hawk.get(HawkConfig.HOME_LOCALE, 0) == 0) {
            LocaleHelper.setLocale(App.this, "zh");
        } else {
            LocaleHelper.setLocale(App.this, "");
        }
    }

    public static App getInstance() {
        return instance;
    }

    private void putDefault(String key, Object value) {
        if (!Hawk.contains(key)) {
            Hawk.put(key, value);
        }
    }

    @Override
    public void onTerminate() {
        super.onTerminate();
        JsLoader.destroy();
    }

    public void setDashData(String data) {
        dashData = data;
    }

    public String getDashData() {
        return dashData;
    }

    public static void startWebserver() {
        if (server != null) return;
        server = AndServer
                .webServer(instance)
                .port(12345)
                .timeout(60, TimeUnit.SECONDS)
                .listener(new Server.ServerListener() {
                    @Override
                    public void onStarted() {

                    }

                    @Override
                    public void onStopped() {

                    }

                    @Override
                    public void onException(Exception e) {

                    }
                }).build();
        server.startup();
    }

    public static void post(Runnable runnable) {
        getInstance().handler.post(runnable);
    }

    public static void post(Runnable runnable, long delayMillis) {
        getInstance().handler.removeCallbacks(runnable);
        if (delayMillis >= 0) getInstance().handler.postDelayed(runnable, delayMillis);
    }
}
