package com.github.tvbox.osc.util;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class LiveSourceMemoryTest {

    @Test
    public void rememberedUrlSurvivesLineReordering() {
        LiveSourceMemory.resetForTest(new LiveSourceMemory.State());
        LiveSourceMemory.rememberUrl("cctv1", "http://b/1.m3u8");
        // 合并新增线路后顺序变了，按 URL 仍能找回原线路
        assertEquals(2, LiveSourceMemory.indexOfPreferred(
                Arrays.asList("http://new/1.m3u8", "http://a/1.m3u8", "http://b/1.m3u8"),
                LiveSourceMemory.preferredUrl("cctv1")));
        assertEquals(0, LiveSourceMemory.indexOfPreferred(Arrays.asList("http://a/1.m3u8"), "http://gone"));
        assertNull(LiveSourceMemory.preferredUrl("unknown"));
    }

    @Test
    public void jsonRoundTrip() {
        LiveSourceMemory.State s = new LiveSourceMemory.State();
        s.preferred.put("cctv1", "http://a");
        s.latency.put("http://a", 850L);
        LiveSourceMemory.State back = LiveSourceMemory.parse(LiveSourceMemory.toJson(s));
        assertEquals("http://a", back.preferred.get("cctv1"));
        assertEquals(Long.valueOf(850L), back.latency.get("http://a"));
        // 损坏数据不抛异常
        assertTrue(LiveSourceMemory.parse("{not json").preferred.isEmpty());
        assertTrue(LiveSourceMemory.parse("").latency.isEmpty());
    }

    @Test
    public void legacyIndexConvertsToUrl() {
        Map<String, Integer> legacy = new HashMap<>();
        legacy.put("CCTV1综合", 1);
        legacy.put("湖南卫视", 9);
        assertEquals("http://b", LiveSourceMemory.legacyIndexToUrl(legacy,
                Arrays.asList("CCTV-1综合", "CCTV1综合"), Arrays.asList("http://a", "http://b")));
        // 越界的旧下标丢弃
        assertNull(LiveSourceMemory.legacyIndexToUrl(legacy,
                Arrays.asList("湖南卫视"), Arrays.asList("http://a")));
        assertNull(LiveSourceMemory.legacyIndexToUrl(legacy,
                Arrays.asList("没有记录"), Arrays.asList("http://a")));
    }

    @Test
    public void memoryIsBounded() {
        LiveSourceMemory.State s = new LiveSourceMemory.State();
        for (int i = 0; i < LiveSourceMemory.MAX_CHANNELS + 50; i++) {
            s.preferred.put("c" + i, "u" + i);
        }
        assertEquals(LiveSourceMemory.MAX_CHANNELS, s.preferred.size());
        assertTrue(s.preferred.containsKey("c" + (LiveSourceMemory.MAX_CHANNELS + 49)));
    }
}
