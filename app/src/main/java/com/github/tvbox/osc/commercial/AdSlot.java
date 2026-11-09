package com.github.tvbox.osc.commercial;

import com.google.gson.annotations.SerializedName;

/**
 * 服务端下发的广告位定义。
 *
 * <p>字段必须带 {@link SerializedName}：release 开启了 minify，
 * 裸字段名会被 R8 改写成 a/b/c，反序列化后全部为空、广告永不展示。
 */
public class AdSlot {
    @SerializedName("placement")
    public String placement;
    @SerializedName("enabled")
    public boolean enabled;
    @SerializedName("title")
    public String title;
    @SerializedName("description")
    public String description;
    @SerializedName("imageUrl")
    public String imageUrl;
    @SerializedName("clickUrl")
    public String clickUrl;
    @SerializedName("durationSeconds")
    public int durationSeconds;
    @SerializedName("closeable")
    public boolean closeable;

    public boolean isValidFor(AdPlacement adPlacement) {
        return enabled
                && placement != null
                && placement.equals(adPlacement.name())
                && title != null
                && !title.trim().isEmpty();
    }
}
