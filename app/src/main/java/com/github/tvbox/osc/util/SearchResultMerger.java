package com.github.tvbox.osc.util;

import android.view.View;

import androidx.recyclerview.widget.RecyclerView;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.github.tvbox.osc.bean.Movie;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 把新到的搜索结果按 {@link SourceRanker} 排序并入列表。
 * 获得焦点的条目及其之前的部分保持不动，新结果只在其后按分数插入，遥控器焦点不会被挤走。
 */
public final class SearchResultMerger {

    public static final SourceRanker.Keys<Movie.Video> VIDEO_KEYS = new SourceRanker.Keys<Movie.Video>() {
        @Override
        public String title(Movie.Video item) {
            return item.name;
        }

        @Override
        public String site(Movie.Video item) {
            return item.sourceKey;
        }
    };

    public static final PlaybackCandidates.IdOf<Movie.Video> VIDEO_ID = new PlaybackCandidates.IdOf<Movie.Video>() {
        @Override
        public String id(Movie.Video item) {
            return item.id;
        }
    };

    private SearchResultMerger() {
    }

    /** @return 合并后列表是否从空变为非空 */
    public static boolean merge(BaseQuickAdapter<Movie.Video, ?> adapter, RecyclerView list,
                                List<Movie.Video> incoming, String query, Map<String, Long> elapsed) {
        if (incoming == null || incoming.isEmpty()) return false;
        SourceRanker.Quality quality = SiteQualityStore.quality();
        List<Movie.Video> data = adapter.getData();
        if (data.isEmpty()) {
            adapter.setNewData(SourceRanker.rank(incoming, VIDEO_KEYS, query, quality, elapsed));
            return true;
        }
        int keep = 0;
        View focused = list == null ? null : list.getFocusedChild();
        if (focused != null) {
            int pos = list.getChildAdapterPosition(focused) - adapter.getHeaderLayoutCount();
            keep = pos + 1;
        }
        keep = Math.max(0, Math.min(keep, data.size()));
        int oldTail = data.size() - keep;
        List<Movie.Video> tail = new ArrayList<>(data.subList(keep, data.size()));
        tail.addAll(incoming);
        List<Movie.Video> rankedTail = SourceRanker.rank(tail, VIDEO_KEYS, query, quality, elapsed);
        data.subList(keep, data.size()).clear();
        data.addAll(rankedTail);
        int start = keep + adapter.getHeaderLayoutCount();
        if (oldTail > 0) adapter.notifyItemRangeChanged(start, oldTail);
        adapter.notifyItemRangeInserted(start + oldTail, rankedTail.size() - oldTail);
        return false;
    }

    /** 生成同名候选 token 并放进 bundle */
    public static void putCandidates(android.os.Bundle bundle, List<Movie.Video> ranked, Movie.Video selected) {
        String token = PlaybackCandidates.put(ranked, VIDEO_KEYS, VIDEO_ID, selected);
        if (token != null) bundle.putString(PlaybackCandidates.EXTRA_TOKEN, token);
    }
}
