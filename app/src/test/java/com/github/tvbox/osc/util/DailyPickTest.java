package com.github.tvbox.osc.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class DailyPickTest {
    private static final List<String> TOP = Arrays.asList("A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K", "L");

    @Test
    public void sameDayIsStable() {
        int first = DailyPick.pick(TOP, "20261109", "", "", Collections.emptyList());
        int again = DailyPick.pick(TOP, "20261109", "20261109", TOP.get(first), Collections.emptyList());
        assertEquals(first, again);
    }

    @Test
    public void onlyPicksFromTopTen() {
        for (int d = 1; d <= 28; d++) {
            int idx = DailyPick.pick(TOP, String.format("202611%02d", d), "", "", Collections.emptyList());
            assertEquals(true, idx >= 0 && idx < DailyPick.POOL_SIZE);
        }
    }

    @Test
    public void noRepeatWithinSevenDays() {
        List<String> history = new ArrayList<>();
        for (int d = 1; d <= 20; d++) {
            List<String> recent = history.subList(Math.max(0, history.size() - DailyPick.RECENT_DAYS), history.size());
            int idx = DailyPick.pick(TOP, String.format("202611%02d", d), "", "", new ArrayList<>(recent));
            String name = TOP.get(idx);
            assertFalse("第 " + d + " 天重复了 " + name, recent.contains(name));
            history.add(name);
        }
    }

    @Test
    public void changesAcrossDaysWhenListIsUnchanged() {
        int day1 = DailyPick.pick(TOP, "20261109", "", "", Collections.emptyList());
        int day2 = DailyPick.pick(TOP, "20261110", "20261109", TOP.get(day1), Collections.singletonList(TOP.get(day1)));
        assertNotEquals(day1, day2);
    }

    @Test
    public void emptyListReturnsMinusOne() {
        assertEquals(-1, DailyPick.pick(Collections.emptyList(), "20261109", "", "", null));
    }
}
