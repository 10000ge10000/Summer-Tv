package com.github.tvbox.osc.util;

import com.github.tvbox.osc.bean.AbsXml;
import com.github.tvbox.osc.bean.Movie;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;

public class MovieRecommendationMergerTest {
    @Test
    public void ranksAcrossSubcategoriesAndDeduplicatesIds() {
        Map<Integer, List<Movie.Video>> data = new HashMap<>();
        data.put(0, Arrays.asList(video("m1", 10, "2026-01-01"), video("shared", 30, "2026-01-02")));
        data.put(1, Arrays.asList(video("m2", 50, "2026-01-03"), video("shared", 5, "2026-01-01")));

        AbsXml merged = MovieRecommendationMerger.merge(data);

        assertEquals(1, merged.movie.pagecount);
        assertEquals(3, merged.movie.recordcount);
        assertEquals("m2", merged.movie.videoList.get(0).id);
        assertEquals("shared", merged.movie.videoList.get(1).id);
        assertEquals("m1", merged.movie.videoList.get(2).id);
    }

    @Test
    public void preservesFullSingleCategoryUpToCategoryMax() {
        Map<Integer, List<Movie.Video>> data = new HashMap<>();
        List<Movie.Video> videos = new java.util.ArrayList<>();
        for (int i = 0; i < 20; i++) {
            videos.add(video("v" + i, 100 - i, "2026-01-01"));
        }
        data.put(0, videos);

        AbsXml merged = MovieRecommendationMerger.merge(data);

        assertEquals(20, merged.movie.recordcount);
        assertEquals(20, merged.movie.videoList.size());
        assertEquals("v0", merged.movie.videoList.get(0).id);
        assertEquals("v19", merged.movie.videoList.get(19).id);
    }

    @Test
    public void capsTotalUniqueRecommendationsAtSixty() {
        Map<Integer, List<Movie.Video>> data = new HashMap<>();
        for (int c = 0; c < 5; c++) {
            List<Movie.Video> videos = new java.util.ArrayList<>();
            for (int i = 0; i < 20; i++) {
                videos.add(video("c" + c + "_v" + i, 1000 - (c * 20 + i), "2026-01-01"));
            }
            data.put(c, videos);
        }

        AbsXml merged = MovieRecommendationMerger.merge(data);

        assertEquals(60, merged.movie.recordcount);
        assertEquals(60, merged.movie.videoList.size());
    }

    @Test
    public void interleavesFairlyWhenHitsAndTimestampsAreEquivalent() {
        Map<Integer, List<Movie.Video>> data = new HashMap<>();
        data.put(0, Arrays.asList(video("a1", 0, null), video("a2", 0, null)));
        data.put(1, Arrays.asList(video("b1", 0, null), video("b2", 0, null)));
        data.put(2, Arrays.asList(video("c1", 0, null), video("c2", 0, null)));

        AbsXml merged = MovieRecommendationMerger.merge(data);

        assertEquals(6, merged.movie.recordcount);
        assertEquals(Arrays.asList("a1", "b1", "c1", "a2", "b2", "c2"),
                Arrays.asList(
                        merged.movie.videoList.get(0).id,
                        merged.movie.videoList.get(1).id,
                        merged.movie.videoList.get(2).id,
                        merged.movie.videoList.get(3).id,
                        merged.movie.videoList.get(4).id,
                        merged.movie.videoList.get(5).id
                ));
    }

    @Test
    public void handlesEmptyAndNullCategoriesGracefully() {
        AbsXml mergedNull = MovieRecommendationMerger.merge(null);
        assertEquals(0, mergedNull.movie.recordcount);
        assertEquals(0, mergedNull.movie.videoList.size());

        Map<Integer, List<Movie.Video>> data = new HashMap<>();
        data.put(0, null);
        data.put(1, java.util.Collections.emptyList());
        data.put(2, Arrays.asList(video("", 10, "2026-01-01"), null));

        AbsXml mergedEmpty = MovieRecommendationMerger.merge(data);
        assertEquals(0, mergedEmpty.movie.recordcount);
        assertEquals(0, mergedEmpty.movie.videoList.size());
    }

    private static Movie.Video video(String id, long hits, String last) {
        Movie.Video video = new Movie.Video();
        video.id = id;
        video.hits = hits;
        video.last = last;
        return video;
    }
}
