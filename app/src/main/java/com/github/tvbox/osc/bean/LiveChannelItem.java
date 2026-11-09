package com.github.tvbox.osc.bean;

import java.util.ArrayList;

/**
 * @author pj567
 * @date :2021/1/12
 * @description:
 */
public class LiveChannelItem {
    /**
     * channelIndex : 频道索引号
     * channelNum : 频道名称
     * channelSourceNames : 频道源名称
     * channelUrls : 频道源地址
     * sourceIndex : 频道源索引
     * sourceNum : 频道源总数
     */
    private int channelIndex;
    private int channelNum;
    private String channelName;
    private ArrayList<String> channelSourceNames;
    private ArrayList<String> channelUrls;
    public int sourceIndex = 0;
    public int sourceNum = 0;
    public boolean include_back = false;

    public void setinclude_back(boolean include_back) {
        this.include_back = include_back;
    }

    public boolean getinclude_back() {
        return include_back;
    }


    public void setChannelIndex(int channelIndex) {
        this.channelIndex = channelIndex;
    }

    public int getChannelIndex() {
        return channelIndex;
    }

    public void setChannelNum(int channelNum) {
        this.channelNum = channelNum;
    }

    public int getChannelNum() {
        return channelNum;
    }

    public void setChannelName(String channelName) {
        this.channelName = channelName;
    }

    public String getChannelName() {
        return channelName;
    }

    public ArrayList<String> getChannelUrls() {
        return channelUrls;
    }

    public void setChannelUrls(ArrayList<String> channelUrls) {
        this.channelUrls = channelUrls;
        sourceNum = channelUrls.size();
    }
    public void preSource() {
        sourceIndex--;
        if (sourceIndex < 0) sourceIndex = sourceNum - 1;
    }
    public void nextSource() {
        sourceIndex++;
        if (sourceIndex == sourceNum) sourceIndex = 0;
    }

    public void setSourceIndex(int sourceIndex) {
        this.sourceIndex = sourceIndex;
    }

    public int getSourceIndex() {
        return sourceIndex;
    }

    public String getUrl() {
        return channelUrls.get(sourceIndex);
    }

    public int getSourceNum() {
        return sourceNum;
    }

    public ArrayList<String> getChannelSourceNames() {
        return channelSourceNames;
    }

    public void setChannelSourceNames(ArrayList<String> channelSourceNames) {
        this.channelSourceNames = channelSourceNames;
    }

    public String getSourceName() {
        return channelSourceNames.get(sourceIndex);
    }

    // ---- 以下字段由 live/LiveGroupNormalizer 填充，均可能为空 ----
    /** 规范化后的频道键（同一频道多线路合并、记忆线路和画面比例都用它） */
    private String channelKey;
    /** m3u 中的 tvg-name / tvg-id / tvg-logo / catchup / catchup-source */
    private String tvgName;
    private String tvgId;
    private String logo;
    private String catchup;
    private String catchupSource;
    /** 合并进本频道的原始频道名，用于兼容旧版按频道名存储的数据 */
    private ArrayList<String> aliases = new ArrayList<>();
    /** 与 channelUrls 一一对应的线路 User-Agent，没有时为 null */
    private ArrayList<String> lineUserAgents = new ArrayList<>();

    public String getChannelKey() {
        return channelKey == null || channelKey.isEmpty() ? channelName : channelKey;
    }

    public void setChannelKey(String channelKey) {
        this.channelKey = channelKey;
    }

    public String getTvgName() {
        return tvgName;
    }

    public void setTvgName(String tvgName) {
        this.tvgName = tvgName;
    }

    public String getTvgId() {
        return tvgId;
    }

    public void setTvgId(String tvgId) {
        this.tvgId = tvgId;
    }

    public String getLogo() {
        return logo;
    }

    public void setLogo(String logo) {
        this.logo = logo;
    }

    public String getCatchup() {
        return catchup;
    }

    public void setCatchup(String catchup) {
        this.catchup = catchup;
    }

    public String getCatchupSource() {
        return catchupSource;
    }

    public void setCatchupSource(String catchupSource) {
        this.catchupSource = catchupSource;
    }

    public ArrayList<String> getAliases() {
        return aliases;
    }

    public void setAliases(ArrayList<String> aliases) {
        this.aliases = aliases == null ? new ArrayList<String>() : aliases;
    }

    public ArrayList<String> getLineUserAgents() {
        return lineUserAgents;
    }

    public void setLineUserAgents(ArrayList<String> lineUserAgents) {
        this.lineUserAgents = lineUserAgents == null ? new ArrayList<String>() : lineUserAgents;
    }

    /** 当前线路的 User-Agent；未指定时返回 null */
    public String getLineUserAgent() {
        if (lineUserAgents == null || sourceIndex < 0 || sourceIndex >= lineUserAgents.size()) return null;
        return lineUserAgents.get(sourceIndex);
    }
}