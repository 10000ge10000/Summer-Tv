package com.github.tvbox.osc.commercial;

import java.util.ArrayList;
import java.util.List;

public class CommercialConfig {
    public boolean adsEnabled;
    public boolean analyticsEnabled;
    public boolean forceAds;
    public boolean tgReportEnabled;
    public String reportEndpoint;
    public String minAppVersion;
    public int rolloutPercent;
    public List<AdSlot> adSlots;

    public static CommercialConfig disabled() {
        CommercialConfig config = new CommercialConfig();
        config.adsEnabled = false;
        config.analyticsEnabled = false;
        config.forceAds = false;
        config.tgReportEnabled = false;
        config.reportEndpoint = "";
        config.minAppVersion = "";
        config.rolloutPercent = 0;
        config.adSlots = new ArrayList<>();
        return config;
    }

    public AdSlot findSlot(AdPlacement placement) {
        if (adSlots == null) return null;
        for (AdSlot slot : adSlots) {
            if (slot != null && slot.isValidFor(placement)) {
                return slot;
            }
        }
        return null;
    }
}
