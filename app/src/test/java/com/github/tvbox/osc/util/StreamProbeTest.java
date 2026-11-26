package com.github.tvbox.osc.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

/** StreamProbe 纯解析逻辑与 SiteQualityStore 短期熔断状态机单元测试 */
public class StreamProbeTest {

    @Test
    public void recognizesDirectAndHlsUrls() {
        assertTrue(StreamProbe.isDirectStreamUrl("https://cdn.example.com/2026/index.m3u8"));
        assertTrue(StreamProbe.isDirectStreamUrl("https://cdn.example.com/play.m3u8?token=abc"));
        assertTrue(StreamProbe.isDirectStreamUrl("http://cdn.example.com/movie.mp4?expires=123"));
        assertTrue(StreamProbe.isHlsUrl("https://cdn.example.com/hls/playlist.m3u8?v=1"));
        assertFalse(StreamProbe.isHlsUrl("https://cdn.example.com/video.mp4"));
        assertFalse(StreamProbe.isDirectStreamUrl("https://v.qq.com/x/cover/abc123.html"));
        assertFalse(StreamProbe.isDirectStreamUrl("push://abc"));
        assertFalse(StreamProbe.isDirectStreamUrl(""));
    }

    @Test
    public void extractsFirstMediaUriAndResolvesRelativePath() {
        String media = "\uFEFF#EXTM3U\n"
                + "#EXT-X-VERSION:3\n"
                + "#EXT-X-TARGETDURATION:6\n"
                + "#EXTINF:6.0,\n"
                + "seg/0001.ts\n"
                + "#EXTINF:6.0,\n"
                + "seg/0002.ts\n";
        assertFalse(StreamProbe.isMasterPlaylist(media));
        assertEquals("https://cdn.example.com/vod/123/seg/0001.ts",
                StreamProbe.extractFirstMediaUri(StreamProbe.stripBom(media), "https://cdn.example.com/vod/123/index.m3u8"));

        String master = "#EXTM3U\n"
                + "#EXT-X-STREAM-INF:BANDWIDTH=2000000\n"
                + "/hls/1080p/index.m3u8\n";
        assertTrue(StreamProbe.isMasterPlaylist(master));
        assertEquals("https://cdn.example.com/hls/1080p/index.m3u8",
                StreamProbe.extractFirstMediaUri(master, "https://cdn.example.com/vod/master.m3u8"));

        assertNull(StreamProbe.extractFirstMediaUri("#EXTM3U\n#EXT-X-ENDLIST\n", "https://a.com/a.m3u8"));
    }

    @Test
    public void detectsHtmlErrorPages() {
        assertTrue(StreamProbe.looksLikeHtmlPage("text/html; charset=utf-8", new byte[]{1, 2, 3}));
        assertTrue(StreamProbe.looksLikeHtmlPage("application/octet-stream",
                "<!DOCTYPE html><html>403</html>".getBytes(StandardCharsets.UTF_8)));
        assertFalse(StreamProbe.looksLikeHtmlPage("video/mp2t", new byte[]{0x47, 0x40, 0x00, 0x10, 0x00, 0x01}));
    }

    @Test
    public void circuitBreakerOpensAfterThresholdAndResetsOnSuccess() {
        SiteQualityStore.Stat st = new SiteQualityStore.Stat();
        long t0 = 1_000_000L;

        SiteQualityStore.applyFailure(st, 1.0, t0);
        SiteQualityStore.applyFailure(st, 1.0, t0 + 1000);
        assertFalse(SiteQualityStore.isCircuitOpen(st, t0 + 2000));

        // 第 3 次连续失败触发 6 小时熔断
        SiteQualityStore.applyFailure(st, 1.0, t0 + 2000);
        assertTrue(SiteQualityStore.isCircuitOpen(st, t0 + 3000));
        assertFalse(SiteQualityStore.isCircuitOpen(st, t0 + 2000 + SiteQualityStore.CIRCUIT_COOLDOWN_MS + 1));

        // 任意一次成功立即清除熔断
        SiteQualityStore.applySuccess(st, 0.5, 800);
        assertFalse(SiteQualityStore.isCircuitOpen(st, t0 + 3000));
        assertEquals(0, st.cf);
    }
}
