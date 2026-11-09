package com.github.tvbox.osc.util;

import com.github.tvbox.osc.bean.VodInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * 详情页线路排序：第一集是直链（m3u8 / mp4 等）的线路在前，需要解析或嗅探的在后；
 * 同档按本机记录的线路质量分，再同档保持站点原顺序。不做网络探测。
 */
public final class LineOrder {

    private LineOrder() {
    }

    public static void apply(VodInfo info, String sourceKey) {
        if (info == null || info.seriesFlags == null || info.seriesMap == null || info.seriesFlags.size() < 2) {
            return;
        }
        List<Boolean> direct = new ArrayList<>();
        List<Double> score = new ArrayList<>();
        for (VodInfo.VodSeriesFlag flag : info.seriesFlags) {
            List<VodInfo.VodSeries> list = info.seriesMap.get(flag.name);
            String url = list == null || list.isEmpty() || list.get(0).url == null ? "" : list.get(0).url;
            direct.add(url.startsWith("http") && DefaultConfig.isVideoFormat(url));
            score.add(SiteQualityStore.score(SiteQualityStore.lineKey(sourceKey, flag.name)));
        }
        info.reorderFlags(SourceRanker.rankLines(direct, score));
    }
}
