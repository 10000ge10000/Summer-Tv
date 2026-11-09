package com.github.tvbox.osc.util;

import java.util.List;

/**
 * 今日精选每日一换：从热门榜前 {@link #POOL_SIZE} 部里按日期选一部，跳过最近几天出现过的。
 * 纯函数，便于单元测试；持久化（今天选了谁、历史记录）由调用方负责。
 */
public final class DailyPick {
    public static final int POOL_SIZE = 10;
    public static final int RECENT_DAYS = 7;

    private DailyPick() {
    }

    /**
     * @param names      候选片名，按热门顺序
     * @param today      当天日期 yyyyMMdd
     * @param savedDay   上次保存的日期；与 today 相同且片名仍在候选中时直接沿用，保证同一天不变
     * @param savedName  上次保存的片名
     * @param recent     最近几天（不含今天）已展示过的片名
     * @return 选中的下标；候选为空时返回 -1
     */
    public static int pick(List<String> names, String today, String savedDay, String savedName, List<String> recent) {
        if (names == null || names.isEmpty()) {
            return -1;
        }
        int n = Math.min(POOL_SIZE, names.size());
        if (today != null && today.equals(savedDay) && savedName != null) {
            int idx = names.indexOf(savedName);
            if (idx >= 0 && idx < n) {
                return idx;
            }
        }
        int hash = today == null ? 0 : today.hashCode();
        int start = ((hash % n) + n) % n;
        for (int i = 0; i < n; i++) {
            int idx = (start + i) % n;
            if (recent == null || !recent.contains(names.get(idx))) {
                return idx;
            }
        }
        return start;
    }
}
