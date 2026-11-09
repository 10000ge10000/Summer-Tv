package com.github.tvbox.osc.bean;

import com.thoughtworks.xstream.annotations.XStreamAlias;
import com.thoughtworks.xstream.annotations.XStreamAsAttribute;
import com.thoughtworks.xstream.annotations.XStreamConverter;
import com.thoughtworks.xstream.annotations.XStreamImplicit;
import com.thoughtworks.xstream.annotations.XStreamOmitField;
import com.thoughtworks.xstream.converters.extended.ToAttributedValueConverter;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * @author pj567
 * @date :2020/12/18
 * @description:
 */
@XStreamAlias("class")
public class MovieSort implements Serializable {
    @XStreamImplicit(itemFieldName = "ty")
    public List<SortData> sortList;

    @XStreamAlias("ty")
    @XStreamConverter(value = ToAttributedValueConverter.class, strings = {"name"})
    public static class SortData implements Serializable, Comparable<SortData> {
        @XStreamAsAttribute
        public String id;
        public String name;
        public int sort = -1;
        public boolean select = false;
        public ArrayList<SortFilter> filters = new ArrayList<>();
        public HashMap<String, String> filterSelect = new HashMap<>();
        public String flag; // 类型

        /** 原始接口分类 ID；分组展示使用虚拟 ID 时仍可追溯真实请求 ID。 */
        @XStreamOmitField
        public String originalId;
        /** 原始接口分类名称，避免规范分组名称覆盖来源名称。 */
        @XStreamOmitField
        public String originalName;
        /** 原始接口父分类 ID；无父级时为空。 */
        @XStreamOmitField
        public String parentId;
        /** 本地分组或来源树中的子分类，子项的 id 始终用于真实请求。 */
        @XStreamOmitField
        public ArrayList<SortData> children = new ArrayList<>();
        /** 本地电影推荐入口覆盖的真实子分类 ID，不作为接口分类发送。 */
        @XStreamOmitField
        public ArrayList<String> recommendationCategoryIds = new ArrayList<>();

        public SortData() {
        }

        public SortData(String id, String name) {
            this.id = id;
            this.name = name;
        }

        public int filterSelectCount() {
            if (filterSelect == null) {
                return 0;
            }
            int count = 0;
            for (String filter : filterSelect.values()) {
                if (filter != null) {
                    count++;
                }
            }
            return count;
        }

        @Override
        public int compareTo(SortData o) {
            return this.sort - o.sort;
        }
    }

    public static class SortFilter {
        public String key;
        public String name;
        public LinkedHashMap<String, String> values;
    }

}
