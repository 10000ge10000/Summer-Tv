package com.github.tvbox.osc.util;

import android.content.Context;
import android.os.Build;

import com.github.tvbox.osc.BuildConfig;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.Locale;

/**
 * 全局未捕获异常兜底：把崩溃堆栈落到本机 filesDir/crash/ 供事后排查。
 *
 * <p>本类只落盘，不做任何上报——上报需用户同意，且当前统计管道尚未验证可用。
 * 落盘后仍交回系统默认 handler，保持原有崩溃行为不变。
 */
public final class CrashHandler implements Thread.UncaughtExceptionHandler {

    private static final String DIR_NAME = "crash";
    private static final int MAX_FILES = 20;

    private final Context context;
    private final Thread.UncaughtExceptionHandler defaultHandler;

    private CrashHandler(Context context, Thread.UncaughtExceptionHandler defaultHandler) {
        this.context = context;
        this.defaultHandler = defaultHandler;
    }

    public static void install(Context context) {
        if (context == null) return;
        Thread.UncaughtExceptionHandler current = Thread.getDefaultUncaughtExceptionHandler();
        if (current instanceof CrashHandler) return;
        Thread.setDefaultUncaughtExceptionHandler(
                new CrashHandler(context.getApplicationContext(), current));
    }

    public static File getCrashDir(Context context) {
        return new File(context.getFilesDir(), DIR_NAME);
    }

    @Override
    public void uncaughtException(Thread thread, Throwable throwable) {
        try {
            writeLog(thread, throwable);
        } catch (Throwable ignored) {
            // 落盘失败不能影响系统原有的崩溃处理
        }
        if (defaultHandler != null) {
            defaultHandler.uncaughtException(thread, throwable);
        }
    }

    private void writeLog(Thread thread, Throwable throwable) throws Exception {
        File dir = getCrashDir(context);
        if (!dir.isDirectory() && !dir.mkdirs()) return;
        trim(dir);

        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        File file = new File(dir, "crash_" + stamp + ".log");

        StringWriter stack = new StringWriter();
        throwable.printStackTrace(new PrintWriter(stack));

        try (OutputStreamWriter writer = new OutputStreamWriter(
                new FileOutputStream(file), StandardCharsets.UTF_8)) {
            writer.write("time=" + stamp + "\n");
            writer.write("version=" + BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")\n");
            writer.write("flavor=" + BuildConfig.FLAVOR + "\n");
            writer.write("device=" + Build.MANUFACTURER + " " + Build.MODEL + "\n");
            writer.write("android=" + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")\n");
            writer.write("abi=" + Arrays.toString(Build.SUPPORTED_ABIS) + "\n");
            writer.write("thread=" + thread.getName() + "\n\n");
            writer.write(stack.toString());
        }
    }

    /** 只保留最近的若干份，避免长期运行把用户存储写满。 */
    private void trim(File dir) {
        File[] files = dir.listFiles((d, name) -> name.startsWith("crash_") && name.endsWith(".log"));
        if (files == null || files.length < MAX_FILES) return;
        Arrays.sort(files, Comparator.comparingLong(File::lastModified));
        for (int i = 0; i <= files.length - MAX_FILES; i++) {
            //noinspection ResultOfMethodCallIgnored
            files[i].delete();
        }
    }
}
