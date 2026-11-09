package com.github.tvbox.osc.util;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class SearchKeywordsTest {

    @Test
    public void keepsOriginalTitleAndTrimsSeasonQualifier() {
        List<String> result = SearchKeywords.from("庆余年 第二季");

        assertEquals("庆余年 第二季", result.get(0));
        assertTrue(result.contains("庆余年"));
    }

    @Test
    public void splitsSeparatorsWithoutNetworkDependency() {
        List<String> result = SearchKeywords.from("三体:动画版/特别篇");

        assertEquals("三体:动画版/特别篇", result.get(0));
        assertTrue(result.contains("三体"));
        assertTrue(result.contains("动画版"));
    }

    @Test
    public void handlesEmptyInput() {
        assertTrue(SearchKeywords.from(null).isEmpty());
        assertTrue(SearchKeywords.from("").isEmpty());
    }
}
