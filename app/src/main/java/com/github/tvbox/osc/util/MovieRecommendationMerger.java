package com.github.tvbox.osc.util;

import com.github.tvbox.osc.bean.AbsXml;
import com.github.tvbox.osc.bean.Movie;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 从每个电影子类的首页结果构建一页跨子类推荐，避免请求空的父分类。 */
public final class MovieRecommendationMerger {
    public static final int MAX_TOTAL_RECOMMENDATIONS = 60;
    public static final int MAX_ITEMS_PER_CATEGORY = 20;
    private static final Comparator<Movie.Video> RANKING = new Comparator<Movie.Video>() {
        @Override public int compare(Movie.Video left, Movie.Video right) {
            int hits = Long.compare(right.hits, left.hits);
            if (hits != 0) return hits;
            String leftTime = left.last == null ? "" : left.last;
            String rightTime = right.last == null ? "" : right.last;
            return rightTime.compareTo(leftTime);
        }
    };

    private MovieRecommendationMerger() { }

    public static AbsXml merge(Map<Integer, List<Movie.Video>> byCategory) {
        List<Movie.Video> candidates = new ArrayList<>();
        if (byCategory != null) {
            List<Integer> positions = new ArrayList<>(byCategory.keySet());
            Collections.sort(positions);
            List<List<Movie.Video>> rankedPerCategory = new ArrayList<>();
            int maxLen = 0;
            for (Integer position : positions) {
                List<Movie.Video> videos = byCategory.get(position);
                if (videos == null) continue;
                List<Movie.Video> ranked = new ArrayList<>();
                for (Movie.Video video : videos) {
                    if (video != null && video.id != null && !video.id.isEmpty()) ranked.add(video);
                }
                Collections.sort(ranked, RANKING);
                int take = Math.min(MAX_ITEMS_PER_CATEGORY, ranked.size());
                if (take > 0) {
                    rankedPerCategory.add(new ArrayList<>(ranked.subList(0, take)));
                    if (take > maxLen) {
                        maxLen = take;
                    }
                }
            }
            for (int i = 0; i < maxLen; i++) {
                for (List<Movie.Video> catList : rankedPerCategory) {
                    if (i < catList.size()) {
                        candidates.add(catList.get(i));
                    }
                }
            }
        }
        Collections.sort(candidates, RANKING);
        List<Movie.Video> unique = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Movie.Video video : candidates) {
            if (seen.add(video.id)) {
                unique.add(video);
                if (unique.size() >= MAX_TOTAL_RECOMMENDATIONS) {
                    break;
                }
            }
        }
        AbsXml result = new AbsXml();
        Movie movie = new Movie();
        movie.page = 1;
        movie.pagecount = 1;
        movie.videoList = unique;
        movie.recordcount = unique.size();
        movie.pagesize = unique.size();
        result.movie = movie;
        return result;
    }
}
