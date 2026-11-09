package com.github.tvbox.osc.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SourceRankerTest {

    private static final class Item {
        final String title;
        final String site;

        Item(String title, String site) {
            this.title = title;
            this.site = site;
        }
    }

    private static final SourceRanker.Keys<Item> KEYS = new SourceRanker.Keys<Item>() {
        @Override
        public String title(Item item) {
            return item.title;
        }

        @Override
        public String site(Item item) {
            return item.site;
        }
    };

    @Test
    public void exactTitleBeatsBetterSite() {
        Item fuzzy = new Item("小城良方 番外", "good");
        Item exact = new Item("小城良方", "bad");
        Map<String, Double> q = new HashMap<>();
        q.put("good", 0.9);
        q.put("bad", 0.1);
        List<Item> out = SourceRanker.rank(Arrays.asList(fuzzy, exact), KEYS, "小城良方", q::get, null);
        assertEquals("bad", out.get(0).site);
    }

    @Test
    public void unknownSiteSitsBetweenGoodAndBad() {
        double unknown = SourceRanker.unknownScore();
        assertTrue(SourceRanker.qualityScore(10, 0, 1500) > unknown);
        assertTrue(SourceRanker.qualityScore(0, 5, 0) < unknown);
    }

    @Test
    public void sameQualityFallsBackToResponseTime() {
        Item slow = new Item("A", "slow");
        Item fast = new Item("A", "fast");
        Map<String, Long> elapsed = new HashMap<>();
        elapsed.put("slow", 4000L);
        elapsed.put("fast", 800L);
        List<Item> out = SourceRanker.rank(Arrays.asList(slow, fast), KEYS, "A", key -> 0.36, elapsed);
        assertEquals("fast", out.get(0).site);
    }

    @Test
    public void comparatorIsTransitiveForCloseScores() {
        // 分数相近、耗时相反的大量站点：阈值比较会让 TimSort 抛 IllegalArgumentException
        java.util.Random r = new java.util.Random(7);
        for (int round = 0; round < 300; round++) {
            Map<String, Double> q = new HashMap<>();
            Map<String, Long> e = new HashMap<>();
            List<Item> items = new java.util.ArrayList<>();
            for (int i = 0; i < 20; i++) {
                q.put("s" + i, 0.30 + r.nextDouble() * 0.08);
                e.put("s" + i, (long) r.nextInt(5000));
            }
            for (int i = 0; i < 80; i++) items.add(new Item("A", "s" + r.nextInt(20)));
            SourceRanker.rank(items, KEYS, "A", q::get, e);
        }
    }

    @Test
    public void decayHalvesAfterHalfLife() {
        assertEquals(0.5, SourceRanker.decay(SourceRanker.HALF_LIFE_MS), 1e-9);
        assertEquals(1.0, SourceRanker.decay(0), 1e-9);
    }

    @Test
    public void directLinesFirstAndStable() {
        List<Integer> order = SourceRanker.rankLines(
                Arrays.asList(false, true, false, true),
                Arrays.asList(0.36, 0.36, 0.36, 0.36));
        assertEquals(Arrays.asList(1, 3, 0, 2), order);
    }

    @Test
    public void alignEpisodeByNameThenNumber() {
        List<String> names = Arrays.asList("第01集", "第02集", "第03集");
        assertEquals(1, SourceRanker.alignEpisode(names, "第02集", 5, 40));
        assertEquals(2, SourceRanker.alignEpisode(names, "3", 0, 40));
        assertEquals(0, SourceRanker.alignEpisode(Arrays.asList("HD"), "正片", 0, 1));
        assertEquals(-1, SourceRanker.alignEpisode(names, "第10集", 9, 40));
    }
}
