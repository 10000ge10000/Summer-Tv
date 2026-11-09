package com.github.tvbox.osc.commercial;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

public class QueuedAnalyticsProviderTest {

    @Test
    public void keepsEventsAddedAfterTheSentSnapshot() {
        AnalyticsEvent first = new AnalyticsEvent("first");
        AnalyticsEvent second = new AnalyticsEvent("second");
        AnalyticsEvent addedWhileSending = new AnalyticsEvent("added");
        List<AnalyticsEvent> queue = Arrays.asList(first, second, addedWhileSending);

        List<AnalyticsEvent> remaining = QueuedAnalyticsProvider.remainingAfterSnapshot(queue, 2);

        assertEquals(1, remaining.size());
        assertSame(addedWhileSending, remaining.get(0));
    }

    @Test
    public void removesAllEventsWhenWholeSnapshotWasSent() {
        List<AnalyticsEvent> queue = Arrays.asList(new AnalyticsEvent("one"));

        assertEquals(0, QueuedAnalyticsProvider.remainingAfterSnapshot(queue, 1).size());
    }
}
