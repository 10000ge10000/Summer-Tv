package com.github.tvbox.osc.live;

import com.github.tvbox.osc.bean.LiveChannelGroup;
import com.github.tvbox.osc.bean.LiveChannelItem;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 直播分组与频道规范化。纯 Java，可单测。
 *
 * <ul>
 *   <li>分组归入固定的标准分组（央视、卫视……其他），去掉 emoji；原先被隐藏的 4K / 其他 / 熊猫分组改为显示；</li>
 *   <li>频道名规范化（CCTV-1综合、CCTV-5+体育赛事……），CCTV-4 欧洲 / 美洲 作为独立频道，不再并进 CCTV-4；</li>
 *   <li>同一分组内的同一频道合并为多线路，保留原有线路名；</li>
 *   <li>央视按台号排序，卫视按固定优先级排序，其余保持来源顺序；</li>
 *   <li>分组定型后统一做全局连续编号。</li>
 * </ul>
 *
 * <p>只合并、整理，不丢弃任何一条有效地址。
 */
public final class LiveGroupNormalizer {

    public static final String GROUP_CCTV = "央视";
    public static final String GROUP_SATELLITE = "卫视";
    public static final String GROUP_LOCAL = "地方";
    public static final String GROUP_HMT = "港澳台";
    public static final String GROUP_SPORTS = "体育";
    public static final String GROUP_NEWS = "新闻财经";
    public static final String GROUP_DOCUMENTARY = "纪录科教";
    public static final String GROUP_MOVIE = "影视剧场";
    public static final String GROUP_KIDS = "少儿动漫";
    public static final String GROUP_INTERNATIONAL = "国际";
    public static final String GROUP_UHD = "4K超清";
    public static final String GROUP_OTHER = "其他";

    public static final List<String> STANDARD_GROUPS = Collections.unmodifiableList(Arrays.asList(
            GROUP_CCTV, GROUP_SATELLITE, GROUP_LOCAL, GROUP_HMT, GROUP_SPORTS, GROUP_NEWS,
            GROUP_DOCUMENTARY, GROUP_MOVIE, GROUP_KIDS, GROUP_INTERNATIONAL, GROUP_UHD, GROUP_OTHER));

    /** 线路排序依据：本机播放成功记忆 > 测速结果 > 原顺序 */
    public interface LineRanker {
        /** 该频道记住的首选线路 URL，没有时返回 null */
        String preferredUrl(String channelKey);

        /** 该线路的测速 / 起播耗时（毫秒），未知时返回负数 */
        long latencyMs(String url);
    }

    private static final String[] CCTV_SUFFIX = {
            "", "综合", "财经", "综艺", "中文国际", "体育", "电影", "国防军事", "电视剧", "纪录",
            "科教", "戏曲", "社会与法", "新闻", "少儿", "音乐", "奥林匹克", "农业农村"};

    /** 卫视固定优先级，未列出的保持来源顺序排在后面 */
    private static final List<String> SATELLITE_PRIORITY = Arrays.asList(
            "湖南", "浙江", "东方", "江苏", "北京", "广东", "深圳", "安徽", "山东", "天津",
            "湖北", "四川", "重庆", "东南", "江西", "河南", "河北", "辽宁", "黑龙江", "吉林");

    /** 名字以「卫视」结尾但属于港澳台 / 境外的频道，不强制归入卫视 */
    private static final Pattern NON_MAINLAND_SATELLITE = Pattern.compile("^(凤凰|星空|华娱|澳亚|澳门|香港|台湾|台灣|鳳凰|翡翠|亚洲|亞洲|国际|國際|中天|东森|東森|TVBS|TVB)");

    private static final Pattern EMOJI_PATTERN = Pattern.compile(
            "[\\x{1F000}-\\x{1FAFF}\\x{2600}-\\x{27BF}\\x{2B00}-\\x{2BFF}\\x{1F1E6}-\\x{1F1FF}\\x{FE00}-\\x{FE0F}\\x{200D}\\x{20E3}\\x{E0020}-\\x{E007F}]");
    private static final Pattern MCP_PATTERN = Pattern.compile("(?i)[\\s\\u3000_\\-]*mcp");
    private static final Pattern CCTV_PATTERN = Pattern.compile("^(?:CCTV|央视)[\\s\\-_·]*(\\d{1,2})(?:[\\s\\-_·]*(\\+|PLUS))?(.*)$");
    private static final Pattern KEY_SUFFIX_PATTERN = Pattern.compile("(hd|fhd|高清|标清|頻道|频道)$");
    private static final Pattern PROVINCE_PATTERN = Pattern.compile(
            "(北京|上海|天津|重庆|河北|山西|辽宁|吉林|黑龙江|江苏|浙江|安徽|福建|江西|山东|河南|湖北|湖南|广东|海南|四川|贵州|云南|陕西|甘肃|青海|内蒙古|广西|西藏|宁夏|新疆|地方|本地|省|市|县)");

    private LiveGroupNormalizer() {
    }

    // ================= 对外入口 =================

    /**
     * 规范化并合并。
     *
     * @param entries         按来源优先级排列的原始记录（先用户维护的远程源，再内置兜底、精选、配置自带）
     * @param defaultLineName 无线路名时的命名格式，如 "线路%d"
     * @param ranker          线路排序依据，可为 null（只按原顺序）
     */
    public static List<LiveChannelGroup> normalize(List<LiveSourceParser.Entry> entries, String defaultLineName, LineRanker ranker) {
        List<LiveChannelGroup> groups = build(entries, defaultLineName);
        if (ranker != null) rankLines(groups, ranker);
        return groups;
    }

    /** 只做合并与分组（线路保持来源顺序），便于在排序前换算旧版下标记忆 */
    public static List<LiveChannelGroup> build(List<LiveSourceParser.Entry> entries, String defaultLineName) {
        LinkedHashMap<String, GroupBuilder> groupMap = new LinkedHashMap<>();
        if (entries != null) {
            for (LiveSourceParser.Entry entry : entries) {
                if (entry == null || isBlank(entry.url)) continue;
                ChannelName channelName = canonicalChannel(entry.name, entry.tvgName);
                if (isBlank(channelName.display)) continue;
                String groupName;
                String password = entry.password == null ? "" : entry.password;
                if (!password.isEmpty()) {
                    // 带密码的分组（仅配置自带源）独立保留，不并入标准分组
                    groupName = cleanGroupName(entry.group);
                } else {
                    groupName = standardGroupOf(entry.group);
                    if (channelName.forcedGroup != null && canForceGroup(groupName, channelName.forcedGroup)) {
                        groupName = channelName.forcedGroup;
                    }
                }
                String groupKey = groupName + "\u0000" + password;
                GroupBuilder group = groupMap.get(groupKey);
                if (group == null) {
                    group = new GroupBuilder(groupName, password);
                    groupMap.put(groupKey, group);
                }
                group.add(channelName, entry);
            }
        }
        List<GroupBuilder> ordered = new ArrayList<>(groupMap.values());
        Collections.sort(ordered, new Comparator<GroupBuilder>() {
            @Override
            public int compare(GroupBuilder a, GroupBuilder b) {
                return Integer.compare(groupOrder(a), groupOrder(b));
            }
        });
        List<LiveChannelGroup> result = new ArrayList<>();
        for (GroupBuilder builder : ordered) {
            LiveChannelGroup group = builder.toGroup(defaultLineName);
            if (!group.getLiveChannels().isEmpty()) result.add(group);
        }
        reindex(result);
        return result;
    }

    /** 按 LineRanker 重排每个频道的线路（URL、线路名、UA 同步移动），排序稳定 */
    public static void rankLines(List<LiveChannelGroup> groups, final LineRanker ranker) {
        if (groups == null || ranker == null) return;
        for (LiveChannelGroup group : groups) {
            for (LiveChannelItem item : group.getLiveChannels()) {
                final ArrayList<String> urls = item.getChannelUrls();
                if (urls == null || urls.size() <= 1) continue;
                final String preferred = ranker.preferredUrl(item.getChannelKey());
                List<Integer> order = new ArrayList<>();
                for (int i = 0; i < urls.size(); i++) order.add(i);
                Collections.sort(order, new Comparator<Integer>() {
                    @Override
                    public int compare(Integer a, Integer b) {
                        int ta = tier(a), tb = tier(b);
                        if (ta != tb) return Integer.compare(ta, tb);
                        if (ta == 1) {
                            int byLatency = Long.compare(ranker.latencyMs(urls.get(a)), ranker.latencyMs(urls.get(b)));
                            if (byLatency != 0) return byLatency;
                        }
                        return Integer.compare(a, b);
                    }

                    private int tier(int index) {
                        String url = urls.get(index);
                        if (preferred != null && preferred.equals(url)) return 0;
                        return ranker.latencyMs(url) >= 0 ? 1 : 2;
                    }
                });
                ArrayList<String> newUrls = new ArrayList<>();
                ArrayList<String> newNames = new ArrayList<>();
                ArrayList<String> newUas = new ArrayList<>();
                for (int index : order) {
                    newUrls.add(urls.get(index));
                    newNames.add(item.getChannelSourceNames().get(index));
                    newUas.add(index < item.getLineUserAgents().size() ? item.getLineUserAgents().get(index) : null);
                }
                item.setChannelUrls(newUrls);
                item.setChannelSourceNames(newNames);
                item.setLineUserAgents(newUas);
                item.setSourceIndex(0);
            }
        }
    }

    /** 分组下标、组内下标、全局连续频道号（从 1 开始） */
    public static void reindex(List<LiveChannelGroup> groups) {
        int channelNum = 1;
        for (int g = 0; g < groups.size(); g++) {
            LiveChannelGroup group = groups.get(g);
            group.setGroupIndex(g);
            for (int i = 0; i < group.getLiveChannels().size(); i++) {
                LiveChannelItem item = group.getLiveChannels().get(i);
                item.setChannelIndex(i);
                item.setChannelNum(channelNum++);
            }
        }
    }

    public static int countChannels(List<LiveChannelGroup> groups) {
        int count = 0;
        for (LiveChannelGroup group : groups) count += group.getLiveChannels().size();
        return count;
    }

    // ================= 分组 =================

    /** 去 emoji / MCP 后缀，压缩空白 */
    public static String cleanGroupName(String raw) {
        if (raw == null) return GROUP_OTHER;
        String name = EMOJI_PATTERN.matcher(raw).replaceAll("");
        name = MCP_PATTERN.matcher(name).replaceAll("");
        name = name.replaceAll("[\\s\\u3000]+", " ").trim();
        return name.isEmpty() ? GROUP_OTHER : name;
    }

    /** 原始分组名 → 标准分组 */
    public static String standardGroupOf(String raw) {
        String name = cleanGroupName(raw);
        if (STANDARD_GROUPS.contains(name)) return name;
        String upper = name.toUpperCase(Locale.ROOT);
        if (upper.contains("4K") || upper.contains("8K") || name.contains("超高清") || name.contains("超清")) return GROUP_UHD;
        if (name.contains("央视") || upper.contains("CCTV") || name.contains("中央")) return GROUP_CCTV;
        if (name.contains("卫视")) return GROUP_SATELLITE;
        if (name.contains("港澳") || name.contains("港台") || name.contains("香港") || name.contains("澳门")
                || name.contains("台湾") || name.contains("台灣") || upper.contains("TVB")) return GROUP_HMT;
        if (name.contains("体育") || name.contains("運動") || name.contains("运动") || name.contains("赛事") || upper.contains("SPORT")) return GROUP_SPORTS;
        if (name.contains("新闻") || name.contains("财经") || name.contains("资讯") || upper.contains("NEWS")) return GROUP_NEWS;
        if (name.contains("纪录") || name.contains("纪实") || name.contains("科教") || name.contains("教育")
                || name.contains("探索") || upper.contains("DOCUMENTARY")) return GROUP_DOCUMENTARY;
        if (name.contains("少儿") || name.contains("动漫") || name.contains("动画") || name.contains("卡通")
                || name.contains("儿童") || upper.contains("KIDS")) return GROUP_KIDS;
        if (name.contains("影视") || name.contains("电影") || name.contains("剧场") || name.contains("影院")
                || name.contains("电视剧") || upper.contains("MOVIE")) return GROUP_MOVIE;
        if (name.contains("国际") || name.contains("欧美") || name.contains("海外") || name.contains("境外")
                || upper.contains("INTERNATIONAL")) return GROUP_INTERNATIONAL;
        if (name.contains("其他") || name.contains("其它") || name.contains("熊猫") || LiveSourceParser.UNGROUPED.equals(name)) {
            return GROUP_OTHER;
        }
        if (PROVINCE_PATTERN.matcher(name).find()) return GROUP_LOCAL;
        return GROUP_OTHER;
    }

    /** 频道名决定的分组（CCTV、卫视）只在原分组不是港澳台 / 国际时覆盖 */
    private static boolean canForceGroup(String groupName, String forcedGroup) {
        if (GROUP_UHD.equals(forcedGroup)) return true;
        return !GROUP_HMT.equals(groupName) && !GROUP_INTERNATIONAL.equals(groupName) && !GROUP_UHD.equals(groupName);
    }

    private static int groupOrder(GroupBuilder group) {
        int index = STANDARD_GROUPS.indexOf(group.name);
        if (!group.password.isEmpty() || index < 0) {
            // 带密码 / 非标准分组排在「其他」之前
            return STANDARD_GROUPS.size() - 1;
        }
        return index == STANDARD_GROUPS.size() - 1 ? STANDARD_GROUPS.size() : index;
    }

    // ================= 频道名 =================

    /** 规范化后的频道名 */
    public static final class ChannelName {
        public final String display;
        public final String key;
        /** 由频道名决定的分组（CCTV → 央视、xx卫视 → 卫视、CCTV-4K → 4K超清），否则为 null */
        public final String forcedGroup;
        /** 央视排序键，非央视为 Integer.MAX_VALUE */
        public final int cctvOrder;

        ChannelName(String display, String key, String forcedGroup, int cctvOrder) {
            this.display = display;
            this.key = key;
            this.forcedGroup = forcedGroup;
            this.cctvOrder = cctvOrder;
        }
    }

    public static ChannelName canonicalChannel(String rawName, String tvgName) {
        String name = rawName == null ? "" : EMOJI_PATTERN.matcher(rawName).replaceAll("");
        name = MCP_PATTERN.matcher(name).replaceAll("");
        name = name.replace('＋', '+').replaceAll("[\\s\\u3000]+", " ").trim();
        if (name.isEmpty() && tvgName != null) name = tvgName.trim();
        if (name.isEmpty()) return new ChannelName("", "", null, Integer.MAX_VALUE);

        String compact = name.replaceAll("[\\s\\-_·.]+", "").toUpperCase(Locale.ROOT);
        // 在保留分隔符的名字上匹配台号，避免「CCTV-1 4K」压缩成「CCTV14K」后被识别成 14 套
        Matcher m = CCTV_PATTERN.matcher(name.toUpperCase(Locale.ROOT));
        if (m.matches()) {
            int num = Integer.parseInt(m.group(1));
            boolean plus = m.group(2) != null;
            String rest = m.group(3) == null ? "" : m.group(3).replaceAll("[\\s\\-_·.]+", "");
            if ((num == 4 || num == 8) && !plus && rest.startsWith("K")) {
                return new ChannelName("CCTV-" + num + "K超高清", "cctv" + num + "k", GROUP_UHD, 1000 + num);
            }
            if (rest.contains("4K") || rest.contains("8K")) {
                String suffix = rest.contains("8K") ? "8K" : "4K";
                String base = num >= 1 && num < CCTV_SUFFIX.length ? "CCTV-" + num + (plus ? "+" : "") : "CCTV-" + num;
                return new ChannelName(base + " " + suffix, "cctv" + num + (plus ? "p" : "") + "_" + suffix.toLowerCase(Locale.ROOT),
                        GROUP_UHD, 1000 + num * 10);
            }
            if (num == 5 && plus) {
                return new ChannelName("CCTV-5+体育赛事", "cctv5p", GROUP_CCTV, 51);
            }
            if (num == 4 && (rest.contains("欧洲") || rest.contains("EUROPE"))) {
                return new ChannelName("CCTV-4欧洲", "cctv4eu", GROUP_CCTV, 42);
            }
            if (num == 4 && (rest.contains("美洲") || rest.contains("AMERICA"))) {
                return new ChannelName("CCTV-4美洲", "cctv4am", GROUP_CCTV, 43);
            }
            if (num >= 1 && num < CCTV_SUFFIX.length && !plus) {
                return new ChannelName("CCTV-" + num + CCTV_SUFFIX[num], "cctv" + num, GROUP_CCTV, num * 10);
            }
        }
        String key = genericKey(name);
        if (name.endsWith("卫视") || compact.matches(".+卫视(HD|高清)?$")) {
            String display = name.replaceAll("(?i)\\s*(HD|高清)$", "");
            if (!NON_MAINLAND_SATELLITE.matcher(display).find()) {
                return new ChannelName(display, genericKey(display), GROUP_SATELLITE, Integer.MAX_VALUE);
            }
        }
        return new ChannelName(name, key, null, Integer.MAX_VALUE);
    }

    /** 合并用的频道键：忽略大小写、空白、连字符以及「高清 / HD / 频道」等后缀 */
    static String genericKey(String name) {
        String key = name.replace('＋', '+').toLowerCase(Locale.ROOT).replaceAll("[\\s\\u3000\\-_·.'’]+", "");
        String stripped = KEY_SUFFIX_PATTERN.matcher(key).replaceAll("");
        return stripped.isEmpty() ? key : stripped;
    }

    // ================= 构建 =================

    private static final class GroupBuilder {
        final String name;
        final String password;
        final LinkedHashMap<String, ChannelBuilder> channels = new LinkedHashMap<>();

        GroupBuilder(String name, String password) {
            this.name = name;
            this.password = password;
        }

        void add(ChannelName channelName, LiveSourceParser.Entry entry) {
            ChannelBuilder channel = channels.get(channelName.key);
            if (channel == null) {
                channel = new ChannelBuilder(channelName, channels.size());
                channels.put(channelName.key, channel);
            }
            channel.add(entry);
        }

        LiveChannelGroup toGroup(String defaultLineName) {
            List<ChannelBuilder> list = new ArrayList<>(channels.values());
            if (GROUP_CCTV.equals(name) || GROUP_UHD.equals(name)) {
                Collections.sort(list, new Comparator<ChannelBuilder>() {
                    @Override
                    public int compare(ChannelBuilder a, ChannelBuilder b) {
                        int c = Integer.compare(a.name.cctvOrder, b.name.cctvOrder);
                        return c != 0 ? c : Integer.compare(a.order, b.order);
                    }
                });
            } else if (GROUP_SATELLITE.equals(name)) {
                Collections.sort(list, new Comparator<ChannelBuilder>() {
                    @Override
                    public int compare(ChannelBuilder a, ChannelBuilder b) {
                        int c = Integer.compare(satelliteOrder(a.name.display), satelliteOrder(b.name.display));
                        return c != 0 ? c : Integer.compare(a.order, b.order);
                    }
                });
            }
            LiveChannelGroup group = new LiveChannelGroup();
            group.setGroupName(name);
            group.setGroupPassword(password);
            ArrayList<LiveChannelItem> items = new ArrayList<>();
            for (ChannelBuilder builder : list) items.add(builder.toItem(defaultLineName));
            group.setLiveChannels(items);
            return group;
        }
    }

    static int satelliteOrder(String display) {
        for (int i = 0; i < SATELLITE_PRIORITY.size(); i++) {
            String prefix = SATELLITE_PRIORITY.get(i);
            if (display.startsWith(prefix + "卫视")) return i;
        }
        return SATELLITE_PRIORITY.size();
    }

    private static final class ChannelBuilder {
        final ChannelName name;
        final int order;
        final ArrayList<String> aliases = new ArrayList<>();
        final ArrayList<String> urls = new ArrayList<>();
        final ArrayList<String> lineNames = new ArrayList<>();
        final ArrayList<String> userAgents = new ArrayList<>();
        String tvgName;
        String tvgId;
        String logo;
        String catchup;
        String catchupSource;

        ChannelBuilder(ChannelName name, int order) {
            this.name = name;
            this.order = order;
        }

        void add(LiveSourceParser.Entry entry) {
            String alias = entry.name == null ? "" : entry.name.trim();
            if (!alias.isEmpty() && !alias.equals(name.display) && !aliases.contains(alias)) aliases.add(alias);
            if (tvgName == null) tvgName = entry.tvgName;
            if (tvgId == null) tvgId = entry.tvgId;
            if (logo == null) logo = entry.logo;
            if (catchup == null) catchup = entry.catchup;
            if (catchupSource == null) catchupSource = entry.catchupSource;
            if (urls.contains(entry.url)) return;
            urls.add(entry.url);
            lineNames.add(entry.lineName);
            userAgents.add(entry.userAgent);
        }

        LiveChannelItem toItem(String defaultLineName) {
            LiveChannelItem item = new LiveChannelItem();
            item.setChannelName(name.display);
            item.setChannelKey(name.key);
            item.setAliases(new ArrayList<>(aliases));
            item.setTvgName(tvgName);
            item.setTvgId(tvgId);
            item.setLogo(logo);
            item.setCatchup(catchup);
            item.setCatchupSource(catchupSource);
            ArrayList<String> names = new ArrayList<>();
            String format = isBlank(defaultLineName) ? "线路%d" : defaultLineName;
            for (int i = 0; i < lineNames.size(); i++) {
                String lineName = lineNames.get(i);
                names.add(isBlank(lineName) ? String.format(Locale.ROOT, format, i + 1) : lineName);
            }
            item.setChannelUrls(new ArrayList<>(urls));
            item.setChannelSourceNames(names);
            item.setLineUserAgents(new ArrayList<>(userAgents));
            item.setSourceIndex(0);
            return item;
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    /** 调试 / 单测用：分组名 → 频道数 */
    public static Map<String, Integer> summary(List<LiveChannelGroup> groups) {
        Map<String, Integer> map = new LinkedHashMap<>();
        for (LiveChannelGroup group : groups) map.put(group.getGroupName(), group.getLiveChannels().size());
        return map;
    }
}
