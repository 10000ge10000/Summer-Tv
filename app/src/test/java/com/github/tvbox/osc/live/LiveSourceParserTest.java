package com.github.tvbox.osc.live;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class LiveSourceParserTest {

    @Test
    public void m3uWithBomAndDirectives() {
        String m3u = "﻿#EXTM3U x-tvg-url=\"http://epg\"\n"
                + "#EXTINF:-1 tvg-id=\"CCTV1\" tvg-name=\"CCTV-1\" tvg-logo=\"http://logo/1.png\" catchup=\"append\" "
                + "catchup-source=\"?playseek=${(b)yyyyMMddHHmmss}\" group-title=\"📺央视频道\",CCTV-1 综合\n"
                + "#EXTVLCOPT:http-user-agent=TestUA/1.0\n"
                + "#EXTVLCOPT:http-referrer=http://ref\n"
                + "#KODIPROP:inputstream=adaptive\n"
                + "http://a/cctv1.m3u8$官方线路\n"
                + "#EXTINF:-1 group-title=\"体育\",CCTV-5\n"
                + "\n"
                + "http://b/cctv5.m3u8|User-Agent=KodiUA\n";
        List<LiveSourceParser.Entry> entries = LiveSourceParser.parse(m3u);
        assertEquals(2, entries.size());
        LiveSourceParser.Entry first = entries.get(0);
        assertEquals("📺央视频道", first.group);
        assertEquals("CCTV-1 综合", first.name);
        // #EXTVLCOPT 不能被当成播放地址
        assertEquals("http://a/cctv1.m3u8", first.url);
        assertEquals("官方线路", first.lineName);
        assertEquals("CCTV-1", first.tvgName);
        assertEquals("CCTV1", first.tvgId);
        assertEquals("http://logo/1.png", first.logo);
        assertEquals("append", first.catchup);
        assertEquals("TestUA/1.0", first.userAgent);
        LiveSourceParser.Entry second = entries.get(1);
        assertEquals("http://b/cctv5.m3u8", second.url);
        assertEquals("KodiUA", second.userAgent);
        assertNull(second.lineName);
    }

    @Test
    public void nameMayContainCommaInsideQuotedAttributes() {
        String m3u = "#EXTM3U\n#EXTINF:-1 tvg-name=\"A,B\" group-title=\"新闻\",Channel, One\nhttp://x/1.m3u8\n";
        LiveSourceParser.Entry entry = LiveSourceParser.parse(m3u).get(0);
        assertEquals("Channel, One", entry.name);
        assertEquals("A,B", entry.tvgName);
    }

    @Test
    public void txtFormatWithGenreAndMultipleUrls() {
        String txt = "﻿央视,#genre#\nCCTV1,http://a/1.m3u8#http://b/1.m3u8$备用\n无效行\n卫视,#genre#\n湖南卫视,rtmp://c/hn\n坏地址,not-a-url\n";
        List<LiveSourceParser.Entry> entries = LiveSourceParser.parse(txt);
        assertEquals(3, entries.size());
        assertEquals("央视", entries.get(0).group);
        assertEquals("http://b/1.m3u8", entries.get(1).url);
        assertEquals("备用", entries.get(1).lineName);
        assertEquals("卫视", entries.get(2).group);
        assertEquals("rtmp://c/hn", entries.get(2).url);
    }

    @Test
    public void emptyInput() {
        assertEquals(0, LiveSourceParser.parse(null).size());
        assertEquals(0, LiveSourceParser.parse("﻿  ").size());
    }
}
