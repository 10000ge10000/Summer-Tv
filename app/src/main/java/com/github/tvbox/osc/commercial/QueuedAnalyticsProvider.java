package com.github.tvbox.osc.commercial;

import com.github.tvbox.osc.BuildConfig;
import com.github.tvbox.osc.util.LOG;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.orhanobut.hawk.Hawk;

import java.io.IOException;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;

public class QueuedAnalyticsProvider implements AnalyticsProvider {
    private static final String QUEUE_KEY = "commercial_analytics_queue";
    private static final int MAX_QUEUE_SIZE = 200;
    private final Gson gson = new Gson();
    private final Type listType = new TypeToken<List<AnalyticsEvent>>() {}.getType();
    private volatile boolean flushing;

    @Override
    public void track(String eventName, Map<String, String> params) {
        // 用户同意是硬前提：未同意时既不入队也不上报，服务端开关无法绕过它
        if (!MonetizationManager.analyticsAllowed()) return;
        CommercialConfig config = MonetizationManager.get().getConfig();
        if (config == null || !config.analyticsEnabled) return;
        AnalyticsEvent event = new AnalyticsEvent(eventName);
        event.params.put("app_version", BuildConfig.VERSION_NAME);
        event.params.put("channel", BuildConfig.FLAVOR);
        event.params.put("device_type", "tv");
        event.params.put("device_id", MonetizationManager.get().getDeviceId());
        if (params != null) {
            event.params.putAll(sanitize(params));
        }
        List<AnalyticsEvent> queue = readQueue();
        queue.add(event);
        while (queue.size() > MAX_QUEUE_SIZE) {
            queue.remove(0);
        }
        Hawk.put(QUEUE_KEY, gson.toJson(queue));
        if (queue.size() >= 10) flush();
    }

    @Override
    public void flush() {
        if (flushing) return;
        if (!MonetizationManager.analyticsAllowed()) return;
        CommercialConfig config = MonetizationManager.get().getConfig();
        if (config == null || !config.analyticsEnabled) return;
        if (config.reportEndpoint == null || config.reportEndpoint.trim().isEmpty()) return;
        List<AnalyticsEvent> queue = readQueue();
        if (queue.isEmpty()) return;
        flushing = true;
        // 只发送本次快照，成功后也只移除这一批。
        // 原实现直接把队列置空，会连带丢弃请求飞行期间新入队的事件。
        final int sentCount = queue.size();
        Map<String, Object> body = new HashMap<>();
        body.put("events", queue);
        // 与商业配置同走独立客户端：上报内容含持久标识符，不应经过 trust-all 通道
        Request request = new Request.Builder()
                .url(config.reportEndpoint.trim())
                .post(RequestBody.create(MediaType.parse("application/json; charset=utf-8"),
                        gson.toJson(body)))
                .build();
        CommercialHttp.client().newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                flushing = false;
                LOG.e("Commercial analytics flush failed: " + e.getMessage());
            }

            @Override
            public void onResponse(Call call, okhttp3.Response response) {
                boolean ok = response.isSuccessful();
                response.close();
                flushing = false;
                if (ok) {
                    dropSent(sentCount);
                } else {
                    LOG.e("Commercial analytics flush rejected: " + response.code());
                }
            }
        });
    }

    /** 移除已成功上报的前 n 条，保留飞行期间新入队的事件。 */
    private synchronized void dropSent(int sentCount) {
        List<AnalyticsEvent> queue = readQueue();
        if (queue.isEmpty()) return;
        List<AnalyticsEvent> remaining = remainingAfterSnapshot(queue, sentCount);
        Hawk.put(QUEUE_KEY, gson.toJson(remaining));
    }

    static List<AnalyticsEvent> remainingAfterSnapshot(List<AnalyticsEvent> queue, int sentCount) {
        if (queue == null || queue.isEmpty()) return new ArrayList<>();
        int safeSentCount = Math.max(sentCount, 0);
        return safeSentCount >= queue.size()
                ? new ArrayList<>()
                : new ArrayList<>(queue.subList(safeSentCount, queue.size()));
    }

    /** 用户撤回同意时清空待发队列，未上报的事件不应继续留存。 */
    public void clearQueue() {
        Hawk.put(QUEUE_KEY, "[]");
    }

    private List<AnalyticsEvent> readQueue() {
        try {
            String json = Hawk.get(QUEUE_KEY, "[]");
            List<AnalyticsEvent> events = gson.fromJson(json, listType);
            return events == null ? new ArrayList<>() : events;
        } catch (Throwable th) {
            return new ArrayList<>();
        }
    }

    private Map<String, String> sanitize(Map<String, String> params) {
        Map<String, String> clean = new HashMap<>();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) continue;
            String key = entry.getKey().toLowerCase();
            if (key.contains("url") || key.contains("source") || key.contains("keyword") || key.contains("query")) {
                continue;
            }
            clean.put(entry.getKey(), entry.getValue());
        }
        return clean;
    }
}
