package com.sstlfsj.rule.config.internal.publish;

import java.util.Map;

/**
 * TIME_WINDOW pre-gate 的 params 类型化视图，仅用于发布期校验。
 * fromEpochMilli/toEpochMilli 均可选：生效起/止（epoch millis，闭区间）；皆空表示无时段约束（fail-open）。
 */
record TimeWindowParams(Long fromEpochMilli, Long toEpochMilli) {

    /** 从裸 gateParams Map 解析，缺失键为 null；值可为 Number 或可解析的 String。 */
    static TimeWindowParams from(Map<String, Object> params) {
        return new TimeWindowParams(
                toLong(params.get("fromEpochMilli")),
                toLong(params.get("toEpochMilli")));
    }

    private static Long toLong(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.longValue();
        return Long.parseLong(v.toString().trim());
    }
}
