package com.github.tvbox.osc.util;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 进程内保存“同名影片的其他站点”，供播放失败时自动换源。
 *
 * <p>搜索页跳详情时写入并把 token 放进 bundle，详情页和播放页原样透传。
 * 进程被杀后 token 失效，播放页会退回到“请返回换源”的提示。
 */
public final class PlaybackCandidates {

    private static final int MAX_LISTS = 8;
    private static final Map<String, List<Candidate>> LISTS = new LinkedHashMap<>();
    private static long counter = 0;

    public static final String EXTRA_TOKEN = "candidateToken";

    public static final class Candidate {
        public final String sourceKey;
        public final String id;
        public final String name;

        public Candidate(String sourceKey, String id, String name) {
            this.sourceKey = sourceKey;
            this.id = id;
            this.name = name;
        }
    }

    private PlaybackCandidates() {
    }

    /**
     * 从已排好序的搜索结果里挑出与所选影片同名的其他站点（保持排序），生成 token。
     * 没有其他同名站点时返回 null。
     */
    public static <T> String put(List<T> ranked, SourceRanker.Keys<T> keys, IdOf<T> idOf, T selected) {
        if (ranked == null || selected == null) return null;
        String name = SourceRanker.normalize(keys.title(selected));
        if (name.isEmpty()) return null;
        List<Candidate> list = new ArrayList<>();
        for (T item : ranked) {
            if (item == selected) continue;
            String site = keys.site(item);
            if (site == null || site.equals(keys.site(selected)) || idOf.id(item) == null) continue;
            if (!name.equals(SourceRanker.normalize(keys.title(item)))) continue;
            boolean dup = false;
            for (Candidate c : list) {
                if (c.sourceKey.equals(site)) {
                    dup = true;
                    break;
                }
            }
            if (!dup) list.add(new Candidate(site, idOf.id(item), keys.title(item)));
        }
        if (list.isEmpty()) return null;
        synchronized (LISTS) {
            String token = "c" + (++counter);
            LISTS.put(token, list);
            Iterator<String> it = LISTS.keySet().iterator();
            while (LISTS.size() > MAX_LISTS && it.hasNext()) {
                it.next();
                it.remove();
            }
            return token;
        }
    }

    public interface IdOf<T> {
        String id(T item);
    }

    /** 返回副本；token 无效时返回空列表 */
    public static List<Candidate> get(String token) {
        if (token == null) return new ArrayList<>();
        synchronized (LISTS) {
            List<Candidate> list = LISTS.get(token);
            return list == null ? new ArrayList<Candidate>() : new ArrayList<>(list);
        }
    }
}
