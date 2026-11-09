package com.github.tvbox.osc.live;

import com.github.tvbox.osc.bean.LiveChannelGroup;
import com.github.tvbox.osc.bean.LiveChannelItem;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class LiveGroupNormalizerTest {

    private static LiveSourceParser.Entry entry(String group, String name, String url) {
        LiveSourceParser.Entry e = new LiveSourceParser.Entry();
        e.group = group;
        e.name = name;
        e.url = url;
        return e;
    }

    private static LiveChannelGroup group(List<LiveChannelGroup> groups, String name) {
        for (LiveChannelGroup g : groups) if (g.getGroupName().equals(name)) return g;
        return null;
    }

    private static LiveChannelItem channel(LiveChannelGroup group, String name) {
        for (LiveChannelItem item : group.getLiveChannels()) if (item.getChannelName().equals(name)) return item;
        return null;
    }

    @Test
    public void groupMapping() {
        assertEquals("央视", LiveGroupNormalizer.standardGroupOf("📺央视频道"));
        assertEquals("卫视", LiveGroupNormalizer.standardGroupOf("📡卫视频道"));
        assertEquals("国际", LiveGroupNormalizer.standardGroupOf("🌍欧美频道"));
        assertEquals("国际", LiveGroupNormalizer.standardGroupOf("🌍国际频道"));
        assertEquals("港澳台", LiveGroupNormalizer.standardGroupOf("🇭🇰港澳频道"));
        assertEquals("地方", LiveGroupNormalizer.standardGroupOf("🏙️地方频道"));
        assertEquals("体育", LiveGroupNormalizer.standardGroupOf("🏀体育频道"));
        assertEquals("新闻财经", LiveGroupNormalizer.standardGroupOf("📰新闻财经"));
        assertEquals("纪录科教", LiveGroupNormalizer.standardGroupOf("纪实"));
        assertEquals("纪录科教", LiveGroupNormalizer.standardGroupOf("教育"));
        assertEquals("影视剧场", LiveGroupNormalizer.standardGroupOf("影视"));
        assertEquals("少儿动漫", LiveGroupNormalizer.standardGroupOf("🧒少儿动漫"));
        // 原先被隐藏的分组改为显示
        assertEquals("4K超清", LiveGroupNormalizer.standardGroupOf("4K频道"));
        assertEquals("其他", LiveGroupNormalizer.standardGroupOf("熊猫"));
        assertEquals("其他", LiveGroupNormalizer.standardGroupOf("其它-MCP"));
        assertEquals("地方", LiveGroupNormalizer.standardGroupOf("湖南频道"));
        assertEquals("其他", LiveGroupNormalizer.standardGroupOf(null));
    }

    @Test
    public void channelNameNormalization() {
        assertEquals("CCTV-1综合", LiveGroupNormalizer.canonicalChannel("CCTV-1", null).display);
        assertEquals("CCTV-1综合", LiveGroupNormalizer.canonicalChannel("CCTV1综合", null).display);
        assertEquals("CCTV-5+体育赛事", LiveGroupNormalizer.canonicalChannel("CCTV5+体育赛事", null).display);
        assertEquals("CCTV-5+体育赛事", LiveGroupNormalizer.canonicalChannel("CCTV-5＋", null).display);
        assertEquals("CCTV-4K超高清", LiveGroupNormalizer.canonicalChannel("CCTV4K", null).display);
        assertEquals("4K超清", LiveGroupNormalizer.canonicalChannel("CCTV-1 4K", null).forcedGroup);
        assertEquals("cctv1", LiveGroupNormalizer.canonicalChannel("CCTV 1 HD", null).key);
        assertEquals("湖南卫视", LiveGroupNormalizer.canonicalChannel("湖南卫视HD", null).display);
        assertEquals("卫视", LiveGroupNormalizer.canonicalChannel("湖南卫视", null).forcedGroup);
        assertNull(LiveGroupNormalizer.canonicalChannel("凤凰卫视中文台", null).forcedGroup);
        assertEquals("CGTN CN", LiveGroupNormalizer.canonicalChannel("CGTN CN", null).display);
    }

    @Test
    public void cctv4EuropeAndAmericaAreNotMerged() {
        List<LiveChannelGroup> groups = LiveGroupNormalizer.normalize(Arrays.asList(
                entry("央视", "CCTV4中文国际", "http://a/4"),
                entry("央视", "CCTV4欧洲", "http://a/4eu"),
                entry("央视", "CCTV4美洲", "http://a/4am"),
                entry("📺央视频道", "CCTV-4", "http://b/4")), "线路%d", null);
        LiveChannelGroup cctv = group(groups, "央视");
        assertNotNull(cctv);
        assertEquals(3, cctv.getLiveChannels().size());
        assertEquals(Arrays.asList("http://a/4", "http://b/4"), channel(cctv, "CCTV-4中文国际").getChannelUrls());
        assertEquals(Arrays.asList("http://a/4eu"), channel(cctv, "CCTV-4欧洲").getChannelUrls());
        assertEquals(Arrays.asList("http://a/4am"), channel(cctv, "CCTV-4美洲").getChannelUrls());
    }

    @Test
    public void mergesLinesKeepsNamesAndSortsGroups() {
        LiveSourceParser.Entry named = entry("📡卫视频道", "湖南卫视", "http://a/hn");
        named.lineName = "官方";
        List<LiveChannelGroup> groups = LiveGroupNormalizer.normalize(Arrays.asList(
                entry("🌍欧美频道", "AMC", "http://x/amc"),
                entry("卫视", "东南卫视", "http://a/dn"),
                named,
                entry("卫视", "湖南卫视", "http://b/hn"),
                entry("卫视", "湖南卫视", "http://a/hn"),
                entry("央视", "CCTV13新闻", "http://a/13"),
                entry("央视", "CCTV5+体育赛事", "http://a/5p"),
                entry("央视", "CCTV-5", "http://a/5"),
                entry("央视", "怀旧剧场", "http://a/hj"),
                entry("📺央视频道", "CCTV-1", "http://a/1")), "线路%d", null);
        assertEquals(Arrays.asList("央视", "卫视", "国际"), new ArrayList<>(LiveGroupNormalizer.summary(groups).keySet()));
        LiveChannelGroup cctv = groups.get(0);
        List<String> names = new ArrayList<>();
        for (LiveChannelItem item : cctv.getLiveChannels()) names.add(item.getChannelName());
        assertEquals(Arrays.asList("CCTV-1综合", "CCTV-5体育", "CCTV-5+体育赛事", "CCTV-13新闻", "怀旧剧场"), names);
        LiveChannelGroup satellite = groups.get(1);
        assertEquals("湖南卫视", satellite.getLiveChannels().get(0).getChannelName());
        LiveChannelItem hunan = satellite.getLiveChannels().get(0);
        // 重复地址去重；保留原线路名，未命名的按来源顺序命名
        assertEquals(Arrays.asList("http://a/hn", "http://b/hn"), hunan.getChannelUrls());
        assertEquals(Arrays.asList("官方", "线路2"), hunan.getChannelSourceNames());
    }

    @Test
    public void globalNumberingIsContinuous() {
        List<LiveChannelGroup> groups = LiveGroupNormalizer.normalize(Arrays.asList(
                entry("央视", "CCTV-1", "http://a/1"),
                entry("央视", "CCTV-2", "http://a/2"),
                entry("卫视", "湖南卫视", "http://a/hn"),
                entry("体育", "NBA TV", "http://a/nba")), "线路%d", null);
        int expected = 1;
        for (int g = 0; g < groups.size(); g++) {
            assertEquals(g, groups.get(g).getGroupIndex());
            for (int i = 0; i < groups.get(g).getLiveChannels().size(); i++) {
                LiveChannelItem item = groups.get(g).getLiveChannels().get(i);
                assertEquals(i, item.getChannelIndex());
                assertEquals(expected++, item.getChannelNum());
            }
        }
        assertEquals(5, expected);
    }

    @Test
    public void rankerOrdersPreferredThenLatencyThenOriginal() {
        List<LiveChannelGroup> groups = LiveGroupNormalizer.normalize(Arrays.asList(
                entry("央视", "CCTV-1", "http://u1"),
                entry("央视", "CCTV-1", "http://u2"),
                entry("央视", "CCTV-1", "http://u3"),
                entry("央视", "CCTV-1", "http://u4")), "线路%d", new LiveGroupNormalizer.LineRanker() {
            @Override
            public String preferredUrl(String channelKey) {
                return "cctv1".equals(channelKey) ? "http://u4" : null;
            }

            @Override
            public long latencyMs(String url) {
                if (url.equals("http://u3")) return 300;
                if (url.equals("http://u2")) return 900;
                return -1;
            }
        });
        LiveChannelItem item = groups.get(0).getLiveChannels().get(0);
        assertEquals(Arrays.asList("http://u4", "http://u3", "http://u2", "http://u1"), item.getChannelUrls());
        // 线路名跟随线路移动
        assertEquals(Arrays.asList("线路4", "线路3", "线路2", "线路1"), item.getChannelSourceNames());
    }

    @Test
    public void passwordGroupsStaySeparate() {
        LiveSourceParser.Entry locked = entry("私密", "CCTV-1", "http://p/1");
        locked.password = "1234";
        List<LiveChannelGroup> groups = LiveGroupNormalizer.normalize(Arrays.asList(
                entry("央视", "CCTV-1", "http://a/1"), locked), "线路%d", null);
        assertEquals(2, groups.size());
        assertEquals("1234", groups.get(1).getGroupPassword());
        assertEquals("", groups.get(0).getGroupPassword());
    }

    /**
     * 守护测试：内置源规范化后不丢任何地址，频道数不少于改造前。
     *
     * <p>改造前（旧 ApiConfig 管线）对打包数据的结果：列表显示 229 条，其中 8 个卫视频道在
     * 「📡卫视频道」和「卫视」两个分组里各出现一次；按旧版频道键去重为 221 个频道；共 253 个地址。
     * 新管线把重复的卫视合并为多线路，并把 CCTV-4 欧洲 / 美洲拆回独立频道。
     */
    @Test
    public void bundledSourcesKeepEveryUrlAndChannel() throws Exception {
        List<LiveSourceParser.Entry> entries = new ArrayList<>();
        for (String raw : new String[]{"user_result.m3u", "verified_live_fallback.m3u", "live_curated.m3u"}) {
            entries.addAll(LiveSourceParser.parse(readRaw(raw)));
        }
        assertTrue("user_result + verified 至少 255 条", entries.size() >= 186 + 69);
        Set<String> inputUrls = new HashSet<>();
        for (LiveSourceParser.Entry e : entries) inputUrls.add(e.url);

        List<LiveChannelGroup> groups = LiveGroupNormalizer.normalize(entries, "线路%d", null);
        Set<String> outputUrls = new HashSet<>();
        Set<String> outputKeys = new HashSet<>();
        for (LiveChannelGroup g : groups) {
            assertFalse("分组不应为空: " + g.getGroupName(), g.getLiveChannels().isEmpty());
            assertTrue("分组名应为标准分组: " + g.getGroupName(), LiveGroupNormalizer.STANDARD_GROUPS.contains(g.getGroupName()));
            for (LiveChannelItem item : g.getLiveChannels()) {
                outputUrls.addAll(item.getChannelUrls());
                outputKeys.add(g.getGroupName() + "/" + item.getChannelKey());
                assertEquals(item.getChannelUrls().size(), item.getChannelSourceNames().size());
            }
        }
        assertEquals("不能丢失任何地址", inputUrls, outputUrls);
        assertTrue("至少保留改造前的 253 个地址", outputUrls.size() >= 253);
        int total = LiveGroupNormalizer.countChannels(groups);
        assertEquals("同一分组内频道键唯一", total, outputKeys.size());
        assertTrue("频道数不能少于改造前去重后的 221 个，实际 " + total, total >= 221);

        // 每条原始记录都能在结果里找到所在频道
        for (LiveSourceParser.Entry e : entries) {
            boolean found = false;
            for (LiveChannelGroup g : groups) {
                for (LiveChannelItem item : g.getLiveChannels()) {
                    if (item.getChannelUrls().contains(e.url)) {
                        found = true;
                        break;
                    }
                }
                if (found) break;
            }
            assertTrue("找不到原始频道 " + e, found);
        }
        LiveChannelGroup cctv = group(groups, "央视");
        assertNotNull(channel(cctv, "CCTV-4欧洲"));
        assertNotNull(channel(cctv, "CCTV-4美洲"));
        assertNotNull(channel(cctv, "CCTV-4中文国际"));
        System.out.println("live groups: " + LiveGroupNormalizer.summary(groups) + ", channels=" + total + ", urls=" + outputUrls.size());
    }

    private static String readRaw(String name) throws Exception {
        File file = new File("src/main/res/raw/" + name);
        if (!file.isFile()) file = new File("app/src/main/res/raw/" + name);
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }
}
