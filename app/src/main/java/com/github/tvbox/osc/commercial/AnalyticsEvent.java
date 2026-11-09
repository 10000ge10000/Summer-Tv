package com.github.tvbox.osc.commercial;

import com.google.gson.annotations.SerializedName;

import java.util.HashMap;
import java.util.Map;

/**
 * 上报给服务端的单条事件。
 *
 * <p>字段必须带 {@link SerializedName}：release 开启了 minify，裸字段名会被 R8
 * 改写，服务端按 name/timestamp/params 取值将全部落空并整条丢弃。
 * v1.0.16 及更早版本正是因此从未成功上报过任何事件。
 */
public class AnalyticsEvent {
    @SerializedName("name")
    public String name;
    @SerializedName("timestamp")
    public long timestamp;
    @SerializedName("params")
    public Map<String, String> params;

    public AnalyticsEvent(String name) {
        this.name = name;
        this.timestamp = System.currentTimeMillis();
        this.params = new HashMap<>();
    }
}
