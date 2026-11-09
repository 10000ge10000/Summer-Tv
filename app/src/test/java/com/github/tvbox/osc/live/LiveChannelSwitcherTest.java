package com.github.tvbox.osc.live;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class LiveChannelSwitcherTest {

    /** sizes[i] 为分组 i 的频道数，locked[i] 为 true 表示需要密码 */
    private static LiveChannelSwitcher.GroupAccess access(final int[] sizes, final boolean[] locked) {
        return new LiveChannelSwitcher.GroupAccess() {
            @Override
            public int groupCount() {
                return sizes.length;
            }

            @Override
            public int channelCount(int groupIndex) {
                return sizes[groupIndex];
            }

            @Override
            public boolean accessible(int groupIndex) {
                return locked == null || !locked[groupIndex];
            }
        };
    }

    @Test
    public void singleGroupCrossModeDoesNotLoopForever() {
        // 原实现：只有一个分组时 do-while 条件永真，主线程卡死
        LiveChannelSwitcher.GroupAccess a = access(new int[]{3}, null);
        assertArrayEquals(new int[]{0, 0}, LiveChannelSwitcher.nextChannel(a, 0, 2, 1, true));
        assertArrayEquals(new int[]{0, 2}, LiveChannelSwitcher.nextChannel(a, 0, 0, -1, true));
    }

    @Test
    public void allOtherGroupsLockedStaysInCurrentGroup() {
        LiveChannelSwitcher.GroupAccess a = access(new int[]{2, 5, 4}, new boolean[]{false, true, true});
        assertArrayEquals(new int[]{0, 0}, LiveChannelSwitcher.nextChannel(a, 0, 1, 1, true));
        assertArrayEquals(new int[]{0, 1}, LiveChannelSwitcher.nextChannel(a, 0, 0, -1, true));
    }

    @Test
    public void crossGroupSkipsLockedAndEmptyGroups() {
        LiveChannelSwitcher.GroupAccess a = access(new int[]{2, 0, 5, 3}, new boolean[]{false, false, true, false});
        assertArrayEquals(new int[]{3, 0}, LiveChannelSwitcher.nextChannel(a, 0, 1, 1, true));
        assertArrayEquals(new int[]{3, 2}, LiveChannelSwitcher.nextChannel(a, 0, 0, -1, true));
        assertArrayEquals(new int[]{0, 0}, LiveChannelSwitcher.nextChannel(a, 3, 2, 1, true));
    }

    @Test
    public void withoutCrossGroupWrapsInsideGroup() {
        LiveChannelSwitcher.GroupAccess a = access(new int[]{2, 5}, null);
        assertArrayEquals(new int[]{0, 0}, LiveChannelSwitcher.nextChannel(a, 0, 1, 1, false));
        assertArrayEquals(new int[]{0, 1}, LiveChannelSwitcher.nextChannel(a, 0, 0, -1, false));
        assertArrayEquals(new int[]{1, 3}, LiveChannelSwitcher.nextChannel(a, 1, 2, 1, false));
    }

    @Test
    public void emptyOrInvalidGroupReturnsNull() {
        assertNull(LiveChannelSwitcher.nextChannel(access(new int[]{0}, null), 0, 0, 1, true));
        assertNull(LiveChannelSwitcher.nextChannel(access(new int[]{}, null), 0, 0, 1, true));
        assertNull(LiveChannelSwitcher.nextChannel(access(new int[]{3}, null), 5, 0, 1, true));
    }

    @Test
    public void findByGlobalNumber() {
        // 25 个分组也能选到（原实现只遍历前 20 个分组，且越界崩溃）
        int[][] nums = new int[25][];
        int n = 1;
        for (int g = 0; g < nums.length; g++) {
            nums[g] = new int[2];
            nums[g][0] = n++;
            nums[g][1] = n++;
        }
        assertArrayEquals(new int[]{0, 0}, LiveChannelSwitcher.findByNumber(nums, 1));
        assertArrayEquals(new int[]{1, 1}, LiveChannelSwitcher.findByNumber(nums, 4));
        assertArrayEquals(new int[]{24, 1}, LiveChannelSwitcher.findByNumber(nums, 50));
        assertNull(LiveChannelSwitcher.findByNumber(nums, 51));
        assertNull(LiveChannelSwitcher.findByNumber(nums, 0));
    }

    @Test
    public void playseekUsesCorrectSeparator() {
        assertEquals("http://a/b.m3u8?playseek=1-2", LiveCatchup.buildPlayseekUrl("http://a/b.m3u8", "1-2"));
        assertEquals("http://a/b.m3u8?x=1&playseek=1-2", LiveCatchup.buildPlayseekUrl("http://a/b.m3u8?x=1", "1-2"));
        assertEquals("http://a/b?playseek=1-2", LiveCatchup.buildPlayseekUrl("http://a/b?", "1-2"));
    }

    @Test
    public void failoverTriesEveryLineOnceThenStops() {
        LiveChannelSwitcher switcher = LiveChannelSwitcher.create();
        switcher.resetForChannel(3);
        assertEquals(LiveChannelSwitcher.FailAction.NEXT_SOURCE, switcher.onFailure());
        assertEquals(LiveChannelSwitcher.FailAction.NEXT_SOURCE, switcher.onFailure());
        // 第三条也失败：停在当前频道，不再自动跳台
        assertEquals(LiveChannelSwitcher.FailAction.ALL_FAILED, switcher.onFailure());
    }

    @Test
    public void successAndManualChangeResetCounter() {
        LiveChannelSwitcher switcher = LiveChannelSwitcher.create();
        switcher.resetForChannel(2);
        assertEquals(LiveChannelSwitcher.FailAction.NEXT_SOURCE, switcher.onFailure());
        switcher.onPlaying();
        assertEquals(LiveChannelSwitcher.FailAction.NEXT_SOURCE, switcher.onFailure());
        switcher.onManualSourceChange();
        assertEquals(LiveChannelSwitcher.FailAction.NEXT_SOURCE, switcher.onFailure());
        assertEquals(LiveChannelSwitcher.FailAction.ALL_FAILED, switcher.onFailure());
        // 换台后重新计数
        switcher.resetForChannel(2);
        assertEquals(LiveChannelSwitcher.FailAction.NEXT_SOURCE, switcher.onFailure());
    }

    @Test
    public void singleLineFailsImmediately() {
        LiveChannelSwitcher switcher = LiveChannelSwitcher.create();
        switcher.resetForChannel(1);
        assertEquals(LiveChannelSwitcher.FailAction.ALL_FAILED, switcher.onFailure());
    }
}
