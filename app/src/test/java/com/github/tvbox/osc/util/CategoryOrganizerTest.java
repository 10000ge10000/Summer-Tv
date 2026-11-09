package com.github.tvbox.osc.util;

import com.github.tvbox.osc.bean.AbsSortJson;
import com.github.tvbox.osc.bean.MovieSort;
import com.google.gson.Gson;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class CategoryOrganizerTest {

    @Test
    public void keepsParentMetadataAndGsonRoundTrip() {
        AbsSortJson json = new AbsSortJson();
        json.classes = new ArrayList<>();
        AbsSortJson.AbsJsonClass item = json.new AbsJsonClass();
        item.type_id = "cat-film";
        item.type_name = "剧情片";
        item.type_pid = "root-film";
        item.type_flag = "fixture";
        json.classes.add(item);

        MovieSort.SortData converted = json.toAbsSortXml().classes.sortList.get(0);
        assertEquals("cat-film", converted.id);
        assertEquals("cat-film", converted.originalId);
        assertEquals("剧情片", converted.originalName);
        assertEquals("root-film", converted.parentId);

        MovieSort.SortData restored = new Gson().fromJson(new Gson().toJson(converted), MovieSort.SortData.class);
        assertEquals(converted.originalId, restored.originalId);
        assertEquals(converted.originalName, restored.originalName);
        assertEquals(converted.parentId, restored.parentId);
        assertNotNull(restored.children);
    }

    @Test
    public void organizesMaskedFixtureWithParentsDefaultsAndRealRequestIds() {
        List<MovieSort.SortData> input = Arrays.asList(
                category("r-film", "电影", null),
                category("m-drama", "剧情片", "r-film"),
                category("m-action", "动作片", "r-film"),
                category("r-series", "剧集", null),
                category("s-domestic", "国产剧", "r-series"),
                category("r-variety", "综艺", null),
                category("v-mainland", "大陆综艺", "r-variety"),
                category("r-animation", "动漫", null),
                category("a-domestic", "国产动漫", "r-animation"),
                category("doc-01", "纪录片", null),
                category("short-01", "微短剧", null),
                category("other-preview", "预告", null),
                category("home-01", "首页", null));

        List<MovieSort.SortData> groups = CategoryOrganizer.organize(input);

        assertEquals(Arrays.asList("电影", "剧集", "综艺", "动漫", "纪录片", "短剧", "其他"), names(groups));
        MovieSort.SortData movie = groups.get(0);
        assertEquals(CategoryOrganizer.GROUP_MOVIE, movie.id);
        assertEquals(Arrays.asList(CategoryOrganizer.MOVIE_RECOMMENDATION, "m-drama", "m-action"), ids(movie.children));
        assertEquals(CategoryOrganizer.MOVIE_RECOMMENDATION, CategoryOrganizer.defaultChild(movie).id);
        assertEquals(Arrays.asList("m-drama", "m-action"), movie.children.get(0).recommendationCategoryIds);
        assertEquals("m-drama", movie.children.get(1).originalId);
        assertEquals("剧情片", movie.children.get(1).originalName);
        assertEquals("r-film", movie.children.get(1).parentId);
        assertEquals("s-domestic", CategoryOrganizer.defaultChild(groups.get(1)).id);
        assertEquals("v-mainland", CategoryOrganizer.defaultChild(groups.get(2)).id);
        assertEquals("a-domestic", CategoryOrganizer.defaultChild(groups.get(3)).id);
        assertEquals("doc-01", CategoryOrganizer.defaultChild(groups.get(4)).id);
        assertEquals("short-01", CategoryOrganizer.defaultChild(groups.get(5)).id);
        assertEquals("other-preview", CategoryOrganizer.defaultChild(groups.get(6)).id);
        assertFalse(CategoryOrganizer.isGroup(movie.children.get(0)));
        assertTrue(CategoryOrganizer.isGroup(movie));
    }

    @Test
    public void fallsBackToControlledNamesAndHidesBlockedBranches() {
        List<MovieSort.SortData> input = Arrays.asList(
                category("orphan-drama", "剧情片", "missing-parent"),
                category("orphan-series", "国产剧", "another-missing-parent"),
                category("blocked-parent", "伦理片", null),
                category("blocked-child", "剧情片", "blocked-parent"),
                category("blocked-variant", "福利专区", null),
                category("unknown-01", "未收录分类", null),
                category("sports-01", "体育", null));

        List<MovieSort.SortData> groups = CategoryOrganizer.organize(input);

        assertEquals(Arrays.asList("电影", "剧集", "其他"), names(groups));
        assertEquals(CategoryOrganizer.MOVIE_RECOMMENDATION, CategoryOrganizer.defaultChild(groups.get(0)).id);
        assertEquals(Arrays.asList(CategoryOrganizer.MOVIE_RECOMMENDATION, "orphan-drama"), ids(groups.get(0).children));
        assertEquals("orphan-series", CategoryOrganizer.defaultChild(groups.get(1)).id);
        assertEquals(Arrays.asList("unknown-01", "sports-01"), ids(groups.get(2).children));
        assertFalse(ids(groups.get(0).children).contains("blocked-child"));
    }

    @Test
    public void deduplicatesAndSafelyFallsBackForCycles() {
        List<MovieSort.SortData> input = Arrays.asList(
                category("cycle-a", "剧情片", "cycle-b"),
                category("cycle-b", "国产剧", "cycle-a"),
                category("cycle-a", "重复分类", null),
                category("fallback-01", "动作片", "cycle-a"));

        List<MovieSort.SortData> groups = CategoryOrganizer.organize(input);

        assertEquals(Arrays.asList("电影", "剧集"), names(groups));
        assertEquals(Arrays.asList(CategoryOrganizer.MOVIE_RECOMMENDATION, "fallback-01"), ids(groups.get(0).children));
        assertEquals(Arrays.asList("cycle-b"), ids(groups.get(1).children));
        assertNull(CategoryOrganizer.defaultChild(null));
    }

    @Test
    public void putsExplicitDocumentaryShortDramaAndAnimationAheadOfParentGroup() {
        List<MovieSort.SortData> input = Arrays.asList(
                category("parent-film", "电影", null),
                category("animation-film", "动画电影", "parent-film"),
                category("documentary-film", "纪录片", "parent-film"),
                category("parent-series", "剧集", null),
                category("short-series", "微短剧", "parent-series"));

        List<MovieSort.SortData> groups = CategoryOrganizer.organize(input);

        assertEquals(Arrays.asList("动漫", "纪录片", "短剧"), names(groups));
        assertEquals(Arrays.asList("animation-film"), ids(groups.get(0).children));
        assertEquals(Arrays.asList("documentary-film"), ids(groups.get(1).children));
        assertEquals(Arrays.asList("short-series"), ids(groups.get(2).children));
    }

    @Test
    public void treatsBroadNamesAsNavigationWhenParentMetadataIsMissing() {
        List<MovieSort.SortData> groups = CategoryOrganizer.organize(Arrays.asList(
                category("broad-movie", "电影片", null),
                category("specific-movie", "动作片", null),
                category("only-variety", "综艺片", null)));

        assertEquals(Arrays.asList("电影", "综艺"), names(groups));
        assertEquals(Arrays.asList(CategoryOrganizer.MOVIE_RECOMMENDATION, "specific-movie"), ids(groups.get(0).children));
        assertEquals(Arrays.asList("only-variety"), ids(groups.get(1).children));
    }

    @Test
    public void movieGroupWithSingleCategoryAlwaysGeneratesRecommendation() {
        List<MovieSort.SortData> input = Arrays.asList(
                category("only-action", "动作片", null));

        List<MovieSort.SortData> groups = CategoryOrganizer.organize(input);

        assertEquals(1, groups.size());
        MovieSort.SortData movie = groups.get(0);
        assertEquals(CategoryOrganizer.GROUP_MOVIE, movie.id);
        assertEquals(Arrays.asList(CategoryOrganizer.MOVIE_RECOMMENDATION, "only-action"), ids(movie.children));
        assertEquals(CategoryOrganizer.MOVIE_RECOMMENDATION, CategoryOrganizer.defaultChild(movie).id);
        assertEquals(Arrays.asList("only-action"), movie.children.get(0).recommendationCategoryIds);
    }

    @Test
    public void handlesShortAndDecoratedCategoryNamesWithoutParent() {
        List<MovieSort.SortData> input = Arrays.asList(
                category("c1", "动作", null),
                category("c2", "【喜剧】", null),
                category("c3", "🔥4K科幻片", null),
                category("c4", "港台剧", null),
                category("c5", "日韩剧", null),
                category("c6", "国漫", null),
                category("c7", "爽文短剧", null),
                category("c8", "预告片", null));

        List<MovieSort.SortData> groups = CategoryOrganizer.organize(input);

        assertEquals(Arrays.asList("电影", "剧集", "动漫", "短剧", "其他"), names(groups));
        MovieSort.SortData movie = groups.get(0);
        assertEquals(CategoryOrganizer.GROUP_MOVIE, movie.id);
        assertEquals(Arrays.asList(CategoryOrganizer.MOVIE_RECOMMENDATION, "c1", "c2", "c3"), ids(movie.children));
        assertEquals(Arrays.asList("c1", "c2", "c3"), movie.children.get(0).recommendationCategoryIds);

        MovieSort.SortData series = groups.get(1);
        assertEquals(Arrays.asList("c4", "c5"), ids(series.children));

        MovieSort.SortData animation = groups.get(2);
        assertEquals(Arrays.asList("c6"), ids(animation.children));

        MovieSort.SortData shortDrama = groups.get(3);
        assertEquals(Arrays.asList("c7"), ids(shortDrama.children));

        MovieSort.SortData other = groups.get(4);
        assertEquals(Arrays.asList("c8"), ids(other.children));
    }

    @Test
    public void sanitizesNullAndBlankCategoryIdsGracefully() {
        MovieSort.SortData valid = category("valid-1", "动作片", null);
        MovieSort.SortData nullId = category(null, "喜剧片", null);
        MovieSort.SortData blankId = category("  ", "爱情片", null);

        List<MovieSort.SortData> groups = CategoryOrganizer.organize(Arrays.asList(valid, nullId, blankId, null));
        assertEquals(1, groups.size());
        assertEquals(Arrays.asList(CategoryOrganizer.MOVIE_RECOMMENDATION, "valid-1"), ids(groups.get(0).children));
        assertEquals(Arrays.asList("valid-1"), groups.get(0).children.get(0).recommendationCategoryIds);
    }

    @Test
    public void blocksAdultCategoriesGracefully() {
        List<MovieSort.SortData> input = Arrays.asList(
                category("c1", "动作片", null),
                category("c2", "成人专区", null),
                category("c3", "美女写真", null),
                category("c4", "香港三级片", null));

        List<MovieSort.SortData> groups = CategoryOrganizer.organize(input);
        assertEquals(1, groups.size());
        assertEquals(CategoryOrganizer.GROUP_MOVIE, groups.get(0).id);
        assertEquals(Arrays.asList(CategoryOrganizer.MOVIE_RECOMMENDATION, "c1"), ids(groups.get(0).children));
    }

    @Test
    public void supportsVarietySuffixAndAlternateDefaultChild() {
        List<MovieSort.SortData> input = Arrays.asList(
                category("v1", "韩综", null),
                category("s1", "欧美剧", null),
                category("s2", "大陆剧", null));

        List<MovieSort.SortData> groups = CategoryOrganizer.organize(input);
        assertEquals(Arrays.asList("剧集", "综艺"), names(groups));
        MovieSort.SortData series = groups.get(0);
        assertEquals("s2", CategoryOrganizer.defaultChild(series).id);
        MovieSort.SortData variety = groups.get(1);
        assertEquals(Arrays.asList("v1"), ids(variety.children));
    }

    @Test
    public void organizesGuoguoShortDramaCategoriesWithoutEscapingToSeries() {
        List<MovieSort.SortData> input = Arrays.asList(
                category("rank_hot", "短剧·热播", null),
                category("rank_daily", "短剧·日榜", null),
                category("rank_weekly", "短剧·周榜", null),
                category("urban", "短剧·都市", null),
                category("costume", "短剧·古装", null),
                category("warrior", "短剧·战神", null));

        List<MovieSort.SortData> groups = CategoryOrganizer.organize(input);
        assertEquals(1, groups.size());
        MovieSort.SortData shortDramaGroup = groups.get(0);
        assertEquals(CategoryOrganizer.GROUP_SHORT_DRAMA, shortDramaGroup.id);
        assertEquals("短剧", shortDramaGroup.name);
        assertEquals("rank_hot", CategoryOrganizer.defaultChild(shortDramaGroup).id);
        assertEquals(Arrays.asList("rank_hot", "rank_daily", "rank_weekly", "urban", "costume", "warrior"), ids(shortDramaGroup.children));
    }

    private static MovieSort.SortData category(String id, String name, String parentId) {
        MovieSort.SortData result = new MovieSort.SortData(id, name);
        result.originalId = id;
        result.originalName = name;
        result.parentId = parentId;
        return result;
    }

    private static List<String> ids(List<MovieSort.SortData> values) {
        List<String> result = new ArrayList<>();
        for (MovieSort.SortData value : values) {
            result.add(value.id);
        }
        return result;
    }

    private static List<String> names(List<MovieSort.SortData> values) {
        List<String> result = new ArrayList<>();
        for (MovieSort.SortData value : values) {
            result.add(value.name);
        }
        return result;
    }
}
