package com.sstlfsj.rule.kernel.api.model;

/** 指标取值来源(契约值,落 node_trace.value_source ENUM、随 MetricValue 流转)。 */
public enum ValueSource {
    PROVIDED, FETCHED;

    /** 持久化/序列化用的字符串标签(== 枚举名,与 DB ENUM 值一致)。 */
    public String tag() {
        return name();
    }
}
