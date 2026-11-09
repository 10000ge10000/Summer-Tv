package com.github.tvbox.osc.live;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class LiveMigrationTest {

    @Test
    public void legacyTimeoutIndexMapsToNewOptions() {
        // 旧：关/5/10/15/20/25/30 秒；新：关/5/10/15/20 秒
        assertEquals(0, LiveMigration.mapLegacyTimeout(0));
        assertEquals(1, LiveMigration.mapLegacyTimeout(1));
        assertEquals(4, LiveMigration.mapLegacyTimeout(4));
        assertEquals(4, LiveMigration.mapLegacyTimeout(5));
        assertEquals(4, LiveMigration.mapLegacyTimeout(6));
        assertEquals(LiveChannelSwitcher.TIMEOUT_DEFAULT_INDEX, LiveMigration.mapLegacyTimeout(9));
        assertEquals(LiveChannelSwitcher.TIMEOUT_DEFAULT_INDEX, LiveMigration.mapLegacyTimeout(-1));
    }

    @Test
    public void timeoutMillisFollowsOptions() {
        assertEquals(0L, LiveChannelSwitcher.timeoutMillis(0));
        assertEquals(5000L, LiveChannelSwitcher.timeoutMillis(1));
        assertEquals(20000L, LiveChannelSwitcher.timeoutMillis(4));
        assertEquals(10000L, LiveChannelSwitcher.timeoutMillis(6));
    }
}
