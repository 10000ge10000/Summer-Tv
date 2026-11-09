package com.github.tvbox.osc.util;

import com.github.tvbox.osc.bean.MovieSort;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 将来源分类整理成固定的首页二级分组。此类不依赖 Android，也不会改写传入的分类列表。
 */
public final class CategoryOrganizer {

    public static final String GROUP_MOVIE = "group:movie";
    public static final String MOVIE_RECOMMENDATION = "group:movie:recommendation";
    public static final String GROUP_SERIES = "group:series";
    public static final String GROUP_VARIETY = "group:variety";
    public static final String GROUP_ANIMATION = "group:animation";
    public static final String GROUP_DOCUMENTARY = "group:documentary";
    public static final String GROUP_SHORT_DRAMA = "group:short-drama";
    public static final String GROUP_OTHER = "group:other";

    private static final String MOVIE = "movie";
    private static final String SERIES = "series";
    private static final String VARIETY = "variety";
    private static final String ANIMATION = "animation";
    private static final String DOCUMENTARY = "documentary";
    private static final String SHORT_DRAMA = "short-drama";
    private static final String OTHER = "other";

    private static final List<String> GROUP_ORDER = Arrays.asList(
            MOVIE, SERIES, VARIETY, ANIMATION, DOCUMENTARY, SHORT_DRAMA, OTHER);

    private static final Map<String, String> GROUP_LABELS;
    private static final Map<String, Set<String>> NAME_GROUPS;
    private static final Set<String> HIDDEN_NAMES = names(
            "伦理片", "伦理", "理论片", "理论", "福利", "福利片",
            "成人", "成人片", "写真", "写真片", "三级", "三级片");
    private static final Set<String> OTHER_NAMES = names("解说", "影视解说", "预告", "预告片", "体育", "体育赛事", "未知", "其他");
    private static final Set<String> HOME_NAMES = names("首页", "主页", "home");
    private static final Set<String> NAVIGATION_NAMES = names(
            "电影", "电影片", "影片", "剧集", "电视剧", "连续剧",
            "综艺", "综艺片", "动漫", "动漫片", "动画");
    private static final Map<String, List<String>> DEFAULT_CHILD_CANDIDATES;

    static {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put(MOVIE, "电影");
        labels.put(SERIES, "剧集");
        labels.put(VARIETY, "综艺");
        labels.put(ANIMATION, "动漫");
        labels.put(DOCUMENTARY, "纪录片");
        labels.put(SHORT_DRAMA, "短剧");
        labels.put(OTHER, "其他");
        GROUP_LABELS = Collections.unmodifiableMap(labels);

        Map<String, Set<String>> groups = new HashMap<>();
        groups.put(MOVIE, names(
                "电影", "电影片", "影片", "微电影",
                "剧情", "剧情片", "动作", "动作片", "喜剧", "喜剧片", "爱情", "爱情片",
                "科幻", "科幻片", "恐怖", "恐怖片", "惊悚", "惊悚片", "悬疑", "悬疑片",
                "犯罪", "犯罪片", "战争", "战争片", "冒险", "冒险片", "奇幻", "奇幻片",
                "武侠", "武侠片", "古装", "古装片", "灾难", "灾难片",
                "警匪", "警匪片", "西部", "西部片", "传记", "传记片", "歌舞", "歌舞片",
                "国产片", "港台片", "日韩片", "欧美片"));
        groups.put(SERIES, names(
                "剧集", "电视剧", "连续剧", "国产剧", "大陆剧", "内地剧",
                "港剧", "香港剧", "港台剧", "台剧", "台湾剧",
                "韩剧", "韩国剧", "日韩剧", "日剧", "日本剧",
                "美剧", "欧美剧", "泰剧", "泰国剧", "海外剧", "新马泰剧", "外剧"));
        groups.put(VARIETY, names(
                "综艺", "综艺片", "综艺节目", "大陆综艺", "国产综艺", "内地综艺",
                "港台综艺", "日韩综艺", "欧美综艺", "韩综", "国综", "日综"));
        groups.put(ANIMATION, names(
                "动漫", "动漫片", "动画", "动画片", "动画电影",
                "国产动漫", "日本动漫", "日韩动漫", "港台动漫", "欧美动漫", "海外动漫",
                "国漫", "日漫", "美漫", "新番", "新番动漫"));
        groups.put(DOCUMENTARY, names(
                "纪录片", "记录片", "纪录", "记录"));
        groups.put(SHORT_DRAMA, names(
                "短剧", "微短剧", "短剧集", "爽文短剧"));
        NAME_GROUPS = Collections.unmodifiableMap(groups);

        Map<String, List<String>> defaultCandidates = new HashMap<>();
        defaultCandidates.put(MOVIE, Arrays.asList(normalize("剧情片"), normalize("动作片")));
        defaultCandidates.put(SERIES, Arrays.asList(normalize("国产剧"), normalize("大陆剧"), normalize("内地剧"), normalize("电视剧")));
        defaultCandidates.put(VARIETY, Arrays.asList(normalize("大陆综艺"), normalize("国产综艺"), normalize("内地综艺"), normalize("综艺节目")));
        defaultCandidates.put(ANIMATION, Arrays.asList(normalize("国产动漫"), normalize("国漫"), normalize("国产动画"), normalize("新番")));
        defaultCandidates.put(SHORT_DRAMA, Arrays.asList(
                normalize("短剧·热播"), normalize("短剧·日榜"), normalize("短剧·周榜"),
                normalize("热播"), normalize("日榜"), normalize("周榜"), normalize("微短剧")));
        DEFAULT_CHILD_CANDIDATES = Collections.unmodifiableMap(defaultCandidates);
    }

    private CategoryOrganizer() {
    }

    /**
     * 返回有可请求分类的大组，顺序固定为电影、剧集、综艺、动漫、纪录片、短剧、其他。
     * 大组自身使用 group:* 虚拟 ID；其 children 均保留来源中的真实请求 ID。
     */
    public static List<MovieSort.SortData> organize(List<MovieSort.SortData> source) {
        if (source == null || source.isEmpty()) {
            return Collections.emptyList();
        }

        LinkedHashMap<String, Node> nodesById = new LinkedHashMap<>();
        for (int index = 0; index < source.size(); index++) {
            MovieSort.SortData item = source.get(index);
            if (item == null || isBlank(item.id) || nodesById.containsKey(item.id)) {
                continue;
            }
            nodesById.put(item.id, new Node(item, index));
        }
        if (nodesById.isEmpty()) {
            return Collections.emptyList();
        }

        for (Node node : nodesById.values()) {
            Node parent = nodesById.get(node.source.parentId);
            if (parent != null && parent != node && !isCycleMember(node, nodesById)) {
                node.parent = parent;
            }
        }
        hideBlockedNodes(nodesById);

        for (Node node : nodesById.values()) {
            if (node.hidden || isHome(node.source)) {
                continue;
            }
            String group = resolveGroup(node, new HashSet<String>());
            node.group = group == null ? OTHER : group;
            node.copy = copyOf(node.source);
        }

        Map<String, List<Node>> groupedNodes = new HashMap<>();
        for (Node node : nodesById.values()) {
            if (node.copy == null) {
                continue;
            }
            List<Node> nodes = groupedNodes.get(node.group);
            if (nodes == null) {
                nodes = new ArrayList<>();
                groupedNodes.put(node.group, nodes);
            }
            nodes.add(node);
        }

        for (Node node : nodesById.values()) {
            if (node.copy != null && node.parent != null && node.parent.copy != null && node.group.equals(node.parent.group)) {
                node.parent.copy.children.add(node.copy);
            }
        }

        ArrayList<MovieSort.SortData> result = new ArrayList<>();
        for (String group : GROUP_ORDER) {
            List<Node> nodes = groupedNodes.get(group);
            if (nodes == null || nodes.isEmpty()) {
                continue;
            }
            List<MovieSort.SortData> children = requestableLeaves(nodes);
            if (!children.isEmpty()) {
                result.add(newGroup(group, children));
            }
        }
        return result;
    }

    /** 返回大组的默认可请求分类：指定子类优先，不存在时取该组首个分类。 */
    public static MovieSort.SortData defaultChild(MovieSort.SortData group) {
        if (group == null || group.children == null || group.children.isEmpty()) {
            return null;
        }
        if (GROUP_MOVIE.equals(group.id)) {
            for (MovieSort.SortData child : group.children) {
                if (MOVIE_RECOMMENDATION.equals(child.id)) {
                    return child;
                }
            }
        }
        List<String> defaultCandidates = DEFAULT_CHILD_CANDIDATES.get(groupKey(group.id));
        if (defaultCandidates != null) {
            for (String candidate : defaultCandidates) {
                for (MovieSort.SortData child : group.children) {
                    if (candidate.equals(normalize(sourceName(child)))) {
                        return child;
                    }
                }
            }
        }
        return group.children.get(0);
    }

    public static boolean isGroup(MovieSort.SortData item) {
        return item != null && GROUP_LABELS.containsKey(groupKey(item.id));
    }

    private static MovieSort.SortData newGroup(String group, List<MovieSort.SortData> children) {
        MovieSort.SortData result = new MovieSort.SortData("group:" + group, GROUP_LABELS.get(group));
        result.children = new ArrayList<>(children);
        if (MOVIE.equals(group) && !children.isEmpty()) {
            MovieSort.SortData recommendation = new MovieSort.SortData(MOVIE_RECOMMENDATION, "热播推荐");
            for (MovieSort.SortData child : children) {
                if (child != null && child.id != null && !child.id.trim().isEmpty()) {
                    recommendation.recommendationCategoryIds.add(child.id);
                }
            }
            result.children.add(0, recommendation);
        }
        return result;
    }

    private static List<MovieSort.SortData> requestableLeaves(List<Node> nodes) {
        ArrayList<Node> leaves = new ArrayList<>();
        boolean hasSpecificCategory = false;
        for (Node node : nodes) {
            if (!NAVIGATION_NAMES.contains(normalize(sourceName(node.source)))) {
                hasSpecificCategory = true;
                break;
            }
        }
        for (Node node : nodes) {
            boolean navigationOnly = hasSpecificCategory
                    && NAVIGATION_NAMES.contains(normalize(sourceName(node.source)));
            if (!hasVisibleChild(node) && !navigationOnly) {
                leaves.add(node);
            }
        }
        sortByIndex(leaves);
        ArrayList<MovieSort.SortData> result = new ArrayList<>();
        for (Node leaf : leaves) {
            result.add(leaf.copy);
        }
        return result;
    }

    private static boolean hasVisibleChild(Node node) {
        for (Node child : node.children) {
            if (child.copy != null) {
                return true;
            }
        }
        return false;
    }

    private static void sortByIndex(List<Node> nodes) {
        Collections.sort(nodes, new Comparator<Node>() {
            @Override
            public int compare(Node left, Node right) {
                return left.index - right.index;
            }
        });
    }

    private static void hideBlockedNodes(Map<String, Node> nodesById) {
        for (Node node : nodesById.values()) {
            node.hidden = isHiddenName(sourceName(node.source));
            node.children.clear();
        }
        for (Node node : nodesById.values()) {
            if (node.parent != null) {
                node.parent.children.add(node);
            }
        }
        boolean changed;
        do {
            changed = false;
            for (Node node : nodesById.values()) {
                if (!node.hidden && node.parent != null && node.parent.hidden) {
                    node.hidden = true;
                    changed = true;
                }
            }
        } while (changed);
    }

    private static String resolveGroup(Node node, Set<String> visiting) {
        String ownName = normalize(sourceName(node.source));
        if (OTHER_NAMES.contains(ownName)) {
            return OTHER;
        }
        String ownGroup = groupFromName(ownName);
        if (DOCUMENTARY.equals(ownGroup) || SHORT_DRAMA.equals(ownGroup) || ANIMATION.equals(ownGroup)) {
            return ownGroup;
        }
        if (!visiting.add(node.source.id)) {
            return ownGroup;
        }
        try {
            if (node.parent != null) {
                String parentGroup = resolveGroup(node.parent, visiting);
                if (parentGroup != null) {
                    return parentGroup;
                }
            }
            return ownGroup;
        } finally {
            visiting.remove(node.source.id);
        }
    }

    private static String groupFromName(String name) {
        if (name == null || name.isEmpty()) return null;
        for (String group : GROUP_ORDER) {
            Set<String> names = NAME_GROUPS.get(group);
            if (names != null && names.contains(name)) {
                return group;
            }
        }
        String cleaned = cleanName(name);
        if (!cleaned.isEmpty() && !cleaned.equals(name)) {
            for (String group : GROUP_ORDER) {
                Set<String> names = NAME_GROUPS.get(group);
                if (names != null && names.contains(cleaned)) {
                    return group;
                }
            }
        }
        String target = cleaned.isEmpty() ? name : cleaned;
        if (OTHER_NAMES.contains(target)) return OTHER;
        if (target.contains("短剧")) return SHORT_DRAMA;
        if (target.contains("纪录") || target.contains("记录")) return DOCUMENTARY;
        if (target.contains("动漫") || target.contains("动画") || target.contains("新番") || target.endsWith("漫")) return ANIMATION;
        if (target.contains("综艺") || target.endsWith("综")) return VARIETY;
        if (target.contains("电视剧") || target.contains("连续剧") || target.contains("剧集") || target.endsWith("剧")) return SERIES;
        if (target.contains("电影") || target.endsWith("片") || target.endsWith("影片")) return MOVIE;
        return null;
    }

    private static String cleanName(String value) {
        if (value == null) return "";
        String s = value.replaceAll("[\\[\\]【】()（）{}<>《》「」『』]", "");
        s = s.replaceAll("(?i)(4k|1080p|720p|hd|bd|vip|hot|超清|高清|蓝光)", "");
        s = s.replaceAll("[^\\u4e00-\\u9fa5a-zA-Z0-9]", "");
        return s.isEmpty() ? normalize(value) : s.toLowerCase(Locale.ROOT);
    }

    private static boolean isCycleMember(Node node, Map<String, Node> nodesById) {
        Set<String> seen = new HashSet<>();
        Node current = node;
        while (current != null && !isBlank(current.source.parentId)) {
            if (!seen.add(current.source.id)) {
                return current.source.id.equals(node.source.id);
            }
            current = nodesById.get(current.source.parentId);
        }
        return false;
    }

    private static MovieSort.SortData copyOf(MovieSort.SortData source) {
        MovieSort.SortData result = new MovieSort.SortData();
        result.id = source.id;
        result.name = source.name;
        result.sort = source.sort;
        result.select = source.select;
        result.filters = source.filters;
        result.filterSelect = source.filterSelect;
        result.flag = source.flag;
        result.originalId = isBlank(source.originalId) ? source.id : source.originalId;
        result.originalName = isBlank(source.originalName) ? source.name : source.originalName;
        result.parentId = source.parentId;
        result.children = new ArrayList<>();
        return result;
    }

    private static String sourceName(MovieSort.SortData item) {
        return item.originalName == null ? item.name : item.originalName;
    }

    private static boolean isHome(MovieSort.SortData item) {
        return HOME_NAMES.contains(normalize(sourceName(item)));
    }

    private static boolean isHiddenName(String value) {
        String normalized = normalize(value);
        for (String blocked : HIDDEN_NAMES) {
            if (!blocked.isEmpty() && normalized.contains(blocked)) return true;
        }
        return false;
    }

    private static String groupKey(String id) {
        if (id == null || !id.startsWith("group:")) {
            return "";
        }
        return id.substring("group:".length());
    }

    private static Set<String> names(String... values) {
        Set<String> result = new HashSet<>();
        for (String value : values) {
            result.add(normalize(value));
        }
        return Collections.unmodifiableSet(result);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static final class Node {
        final MovieSort.SortData source;
        final int index;
        final ArrayList<Node> children = new ArrayList<>();
        Node parent;
        MovieSort.SortData copy;
        String group;
        boolean hidden;

        Node(MovieSort.SortData source, int index) {
            this.source = source;
            this.index = index;
        }
    }
}
