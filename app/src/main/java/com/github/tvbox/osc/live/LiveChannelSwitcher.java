package com.github.tvbox.osc.live;

/**
 * 直播换台 / 换线 / 超时的纯逻辑状态机，不依赖 Android，便于单测。
 *
 * <p>LivePlayActivity 只负责把播放器状态和按键转交给这里，再按返回的动作去播放。
 */
public final class LiveChannelSwitcher {

    /** 分组访问能力：由界面层提供，避免这里依赖具体的 bean 和密码逻辑 */
    public interface GroupAccess {
        int groupCount();

        int channelCount(int groupIndex);

        /** 分组可直接进入（无密码或已验证） */
        boolean accessible(int groupIndex);
    }

    /** 超时换线选项（与设置菜单下标一一对应）：关 / 5s / 10s / 15s / 20s */
    public static final int[] TIMEOUT_SECONDS = {0, 5, 10, 15, 20};
    public static final int TIMEOUT_DEFAULT_INDEX = 2;

    /** 一次播放失败后的处理动作 */
    public enum FailAction {
        /** 切到下一条线路 */
        NEXT_SOURCE,
        /** 全部线路都试过了，停在当前频道 */
        ALL_FAILED
    }

    private int sourceCount = 0;
    private int triedSources = 0;

    private LiveChannelSwitcher() {
    }

    public static LiveChannelSwitcher create() {
        return new LiveChannelSwitcher();
    }

    /**
     * 计算上一个 / 下一个频道。
     *
     * <p>跨分组时最多遍历一圈分组；找不到其他可用分组（只有一个分组、其余都需要密码或为空）
     * 就在当前分组内循环，不会死循环。
     *
     * @return {groupIndex, channelIndex}；当前分组无频道时返回 null
     */
    public static int[] nextChannel(GroupAccess access, int groupIndex, int channelIndex,
                                    int direction, boolean crossGroup) {
        int groupCount = access.groupCount();
        if (groupCount <= 0 || groupIndex < 0 || groupIndex >= groupCount) return null;
        int size = access.channelCount(groupIndex);
        if (size <= 0) return null;
        int step = direction >= 0 ? 1 : -1;
        int target = channelIndex + step;
        if (target >= 0 && target < size) {
            return new int[]{groupIndex, target};
        }
        if (crossGroup) {
            int g = groupIndex;
            for (int i = 1; i < groupCount; i++) {
                g = ((g + step) % groupCount + groupCount) % groupCount;
                if (g == groupIndex) break;
                int count = access.channelCount(g);
                if (count > 0 && access.accessible(g)) {
                    return new int[]{g, step > 0 ? 0 : count - 1};
                }
            }
        }
        // 不跨分组，或找不到其他可用分组：在当前分组内循环
        return new int[]{groupIndex, step > 0 ? 0 : size - 1};
    }

    /**
     * 按全局连续编号查找频道。频道号在分组定型后统一从 1 开始连续编号，
     * 界面显示的号码与这里的解析规则一致。
     *
     * @param channelNums 每个分组内各频道的号码
     * @return {groupIndex, channelIndex}，号码不存在时返回 null
     */
    public static int[] findByNumber(int[][] channelNums, int number) {
        if (channelNums == null || number <= 0) return null;
        for (int g = 0; g < channelNums.length; g++) {
            int[] nums = channelNums[g];
            if (nums == null) continue;
            for (int i = 0; i < nums.length; i++) {
                if (nums[i] == number) return new int[]{g, i};
            }
        }
        return null;
    }

    /** 超时设置下标 → 毫秒；0 表示关闭超时换线。下标越界时回落到默认值 */
    public static long timeoutMillis(int optionIndex) {
        if (optionIndex < 0 || optionIndex >= TIMEOUT_SECONDS.length) {
            optionIndex = TIMEOUT_DEFAULT_INDEX;
        }
        return TIMEOUT_SECONDS[optionIndex] * 1000L;
    }

    /** 规整超时设置下标，防止旧数据越界 */
    public static int clampTimeoutIndex(int optionIndex) {
        if (optionIndex < 0 || optionIndex >= TIMEOUT_SECONDS.length) return TIMEOUT_DEFAULT_INDEX;
        return optionIndex;
    }

    /** 换台（手动或自动）后调用：重新开始计数 */
    public void resetForChannel(int sourceCount) {
        this.sourceCount = Math.max(sourceCount, 0);
        this.triedSources = 0;
    }

    /** 播放成功：清零失败计数，后续再失败重新轮一圈线路 */
    public void onPlaying() {
        triedSources = 0;
    }

    /** 用户手动换线：从新线路开始重新计数 */
    public void onManualSourceChange() {
        triedSources = 0;
    }

    /** 当前线路失败（报错或超时） */
    public FailAction onFailure() {
        triedSources++;
        if (sourceCount <= 1 || triedSources >= sourceCount) {
            return FailAction.ALL_FAILED;
        }
        return FailAction.NEXT_SOURCE;
    }

    int triedSources() {
        return triedSources;
    }
}
