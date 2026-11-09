package com.github.tvbox.osc.util.js;

import com.github.tvbox.osc.util.LOG;
import com.whl.quickjs.android.QuickJSLoader;

/**
 * QuickJS 运行时的懒初始化。
 *
 * <p>此前 {@code QuickJSLoader.init()} 在 Application.onCreate 中调用，
 * 后来被整段删除，导致 .js 爬虫源在 createContext 时直接抛异常且无任何提示。
 *
 * <p>改为按需初始化：只有真正加载 .js 源时才付这份成本，
 * 且初始化失败只影响该类源，不会拖垮应用启动。
 */
public final class QuickJsRuntime {

    private static volatile boolean initialized;
    private static volatile boolean failed;

    private QuickJsRuntime() {
    }

    /**
     * 确保 QuickJS 已初始化。
     *
     * @throws IllegalStateException 初始化失败时抛出，由调用方决定如何提示用户；
     *                               失败会被记住，不会每次重试徒增等待
     */
    public static void ensureInitialized() {
        if (initialized) return;
        if (failed) {
            throw new IllegalStateException("QuickJS 运行时不可用，无法加载 .js 爬虫源");
        }
        synchronized (QuickJsRuntime.class) {
            if (initialized) return;
            try {
                QuickJSLoader.init();
                initialized = true;
            } catch (Throwable th) {
                failed = true;
                LOG.e("QuickJS init failed: " + th.getMessage());
                throw new IllegalStateException("QuickJS 运行时不可用，无法加载 .js 爬虫源", th);
            }
        }
    }
}
