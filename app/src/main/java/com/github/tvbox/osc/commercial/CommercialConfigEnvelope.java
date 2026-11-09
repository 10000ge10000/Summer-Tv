package com.github.tvbox.osc.commercial;

import com.google.gson.annotations.SerializedName;

public class CommercialConfigEnvelope {
    public CommercialConfigPayload payload;
    public String signature;

    public static class CommercialConfigPayload {
        @SerializedName("ads_enabled")
        public boolean adsEnabled;
        @SerializedName("analytics_enabled")
        public boolean analyticsEnabled;
        @SerializedName("force_ads")
        public boolean forceAds;
        @SerializedName("tg_report_enabled")
        public boolean tgReportEnabled;
        @SerializedName("report_endpoint")
        public String reportEndpoint;
        @SerializedName("min_app_version")
        public String minAppVersion;
        @SerializedName("rollout_percent")
        public int rolloutPercent;
        @SerializedName("ad_slots")
        public java.util.List<AdSlot> adSlots;

        CommercialConfig toConfig() {
            CommercialConfig config = new CommercialConfig();
            config.adsEnabled = adsEnabled;
            config.analyticsEnabled = analyticsEnabled;
            config.forceAds = forceAds;
            config.tgReportEnabled = tgReportEnabled;
            config.reportEndpoint = reportEndpoint == null ? "" : reportEndpoint;
            config.minAppVersion = minAppVersion == null ? "" : minAppVersion;
            config.rolloutPercent = Math.max(0, Math.min(100, rolloutPercent));
            config.adSlots = adSlots == null ? new java.util.ArrayList<AdSlot>() : adSlots;
            return config;
        }
    }
}
