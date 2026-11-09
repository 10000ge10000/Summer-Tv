package com.github.tvbox.osc.commercial;

import java.util.Map;

public interface AnalyticsProvider {
    void track(String eventName, Map<String, String> params);
    void flush();
}
