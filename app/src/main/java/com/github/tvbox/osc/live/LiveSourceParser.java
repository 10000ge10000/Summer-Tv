package com.github.tvbox.osc.live;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 直播列表解析：支持 M3U 与 TXT（分组,#genre# / 频道,地址）两种格式。纯 Java，可单测。
 *
 * <p>相对旧的 TxtSubscribe：处理 UTF-8 BOM；跳过 #EXTVLCOPT / #KODIPROP 等指令行，
 * 不再把它们当成播放地址；保留 tvg-name / tvg-id / tvg-logo / catchup / catchup-source /
 * user-agent，以及地址末尾的 {@code $线路名}。
 */
public final class LiveSourceParser {

    public static final String UNGROUPED = "未分组";

    /** 一条「频道 + 线路」记录 */
    public static final class Entry {
        public String group = UNGROUPED;
        public String name = "";
        public String url = "";
        /** 地址里 $ 之后的线路名，没有时为 null */
        public String lineName;
        public String tvgName;
        public String tvgId;
        public String logo;
        public String catchup;
        public String catchupSource;
        public String userAgent;
        /** 分组密码：只有配置自带的直播源才会设置，内置源始终为空 */
        public String password = "";

        @Override
        public String toString() {
            return group + "/" + name + " -> " + url;
        }
    }

    private static final Pattern ATTR_PATTERN = Pattern.compile("([A-Za-z0-9_-]+)=\"([^\"]*)\"");
    private static final Pattern SCHEME_PATTERN = Pattern.compile("^[A-Za-z][A-Za-z0-9+.-]*://.+");

    private LiveSourceParser() {
    }

    public static String stripBom(String content) {
        if (content == null) return "";
        return !content.isEmpty() && content.charAt(0) == '﻿' ? content.substring(1) : content;
    }

    public static boolean isM3u(String content) {
        String text = stripBom(content).trim();
        return text.startsWith("#EXTM3U") || text.startsWith("#EXTINF");
    }

    public static List<Entry> parse(String content) {
        String text = stripBom(content);
        if (text.trim().isEmpty()) return new ArrayList<>();
        return isM3u(text) ? parseM3u(text) : parseTxt(text);
    }

    // ---------------- M3U ----------------

    static List<Entry> parseM3u(String text) {
        List<Entry> result = new ArrayList<>();
        String headerCatchup = null;
        String headerCatchupSource = null;
        Entry pending = null;
        String pendingGroup = null;
        try (BufferedReader reader = new BufferedReader(new StringReader(text))) {
            String raw;
            while ((raw = reader.readLine()) != null) {
                String line = raw.trim();
                if (line.isEmpty()) continue;
                if (line.startsWith("#EXTM3U")) {
                    headerCatchup = attr(line, "catchup");
                    headerCatchupSource = attr(line, "catchup-source");
                    continue;
                }
                if (line.startsWith("#EXTINF")) {
                    pending = parseExtInf(line);
                    if (pending.catchup == null) pending.catchup = headerCatchup;
                    if (pending.catchupSource == null) pending.catchupSource = headerCatchupSource;
                    pendingGroup = null;
                    continue;
                }
                if (line.startsWith("#")) {
                    if (pending == null) continue;
                    String upper = line.toUpperCase(Locale.ROOT);
                    if (upper.startsWith("#EXTVLCOPT:")) {
                        String opt = line.substring("#EXTVLCOPT:".length()).trim();
                        int eq = opt.indexOf('=');
                        if (eq > 0 && opt.substring(0, eq).trim().equalsIgnoreCase("http-user-agent")) {
                            pending.userAgent = opt.substring(eq + 1).trim();
                        }
                    } else if (upper.startsWith("#EXTGRP:")) {
                        pendingGroup = line.substring("#EXTGRP:".length()).trim();
                    }
                    // 其他指令（#KODIPROP、#EXT-X-* 等）一律忽略
                    continue;
                }
                if (pending == null) continue;
                Entry entry = pending;
                pending = null;
                if (UNGROUPED.equals(entry.group) && pendingGroup != null && !pendingGroup.isEmpty()) {
                    entry.group = pendingGroup;
                }
                if (applyUrl(entry, line)) result.add(entry);
            }
        } catch (IOException ignored) {
            // StringReader 不会抛出
        }
        return result;
    }

    static Entry parseExtInf(String line) {
        Entry entry = new Entry();
        int comma = firstCommaOutsideQuotes(line);
        String meta = comma >= 0 ? line.substring(0, comma) : line;
        String name = comma >= 0 ? line.substring(comma + 1).trim() : "";
        Matcher matcher = ATTR_PATTERN.matcher(meta);
        while (matcher.find()) {
            String key = matcher.group(1).toLowerCase(Locale.ROOT);
            String value = matcher.group(2).trim();
            if (value.isEmpty()) continue;
            switch (key) {
                case "group-title":
                    entry.group = value;
                    break;
                case "tvg-name":
                    entry.tvgName = value;
                    break;
                case "tvg-id":
                    entry.tvgId = value;
                    break;
                case "tvg-logo":
                    entry.logo = value;
                    break;
                case "catchup":
                    entry.catchup = value;
                    break;
                case "catchup-source":
                    entry.catchupSource = value;
                    break;
                case "user-agent":
                case "http-user-agent":
                    entry.userAgent = value;
                    break;
                default:
                    break;
            }
        }
        if (name.isEmpty()) name = entry.tvgName == null ? "" : entry.tvgName;
        entry.name = name;
        return entry;
    }

    private static int firstCommaOutsideQuotes(String line) {
        boolean inQuote = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') inQuote = !inQuote;
            else if (c == ',' && !inQuote) return i;
        }
        return -1;
    }

    private static String attr(String line, String key) {
        Matcher matcher = ATTR_PATTERN.matcher(line);
        while (matcher.find()) {
            if (matcher.group(1).equalsIgnoreCase(key)) {
                String value = matcher.group(2).trim();
                return value.isEmpty() ? null : value;
            }
        }
        return null;
    }

    // ---------------- TXT ----------------

    static List<Entry> parseTxt(String text) {
        List<Entry> result = new ArrayList<>();
        String group = UNGROUPED;
        try (BufferedReader reader = new BufferedReader(new StringReader(text))) {
            String raw;
            while ((raw = reader.readLine()) != null) {
                String line = raw.trim();
                if (line.isEmpty() || line.startsWith("//")) continue;
                String[] split = line.split(",", 2);
                if (split.length < 2) continue;
                if (split[1].trim().startsWith("#genre#")) {
                    group = split[0].trim().isEmpty() ? UNGROUPED : split[0].trim();
                    continue;
                }
                if (line.startsWith("#")) continue;
                String name = split[0].trim();
                for (String part : split[1].trim().split("#")) {
                    Entry entry = new Entry();
                    entry.group = group;
                    entry.name = name;
                    if (applyUrl(entry, part.trim())) result.add(entry);
                }
            }
        } catch (IOException ignored) {
            // StringReader 不会抛出
        }
        return result;
    }

    /**
     * 解析地址行：支持 {@code url$线路名} 与 Kodi 风格的 {@code url|User-Agent=xxx}。
     *
     * @return 地址合法（带协议头）时返回 true
     */
    static boolean applyUrl(Entry entry, String value) {
        if (value == null) return false;
        String url = value.trim();
        int pipe = url.indexOf('|');
        if (pipe > 0) {
            String options = url.substring(pipe + 1);
            url = url.substring(0, pipe).trim();
            for (String opt : options.split("&")) {
                int eq = opt.indexOf('=');
                if (eq > 0 && opt.substring(0, eq).trim().equalsIgnoreCase("User-Agent")) {
                    entry.userAgent = opt.substring(eq + 1).trim();
                }
            }
        }
        int dollar = url.indexOf('$');
        if (dollar > 0) {
            String lineName = url.substring(dollar + 1).trim();
            url = url.substring(0, dollar).trim();
            if (!lineName.isEmpty()) entry.lineName = lineName;
        }
        if (!SCHEME_PATTERN.matcher(url).matches()) return false;
        entry.url = url;
        return true;
    }
}
