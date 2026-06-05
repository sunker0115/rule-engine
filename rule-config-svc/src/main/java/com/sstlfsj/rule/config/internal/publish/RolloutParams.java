package com.sstlfsj.rule.config.internal.publish;

import java.util.Map;

/**
 * ROLLOUT pre-gate 的 params 类型化视图，仅用于发布期校验。
 * 字段均可选：percentage 为百分比模式；bucketStart/bucketEnd 为桶区间（互斥）模式；experimentId 为共享分桶种子。
 */
record RolloutParams(Integer percentage, Integer bucketStart, Integer bucketEnd, String experimentId) {

    /** 从裸 gateParams Map 解析，缺失键为 null；值可为 Number 或可解析的 String。 */
    static RolloutParams from(Map<String, Object> params) {
        return new RolloutParams(
                toInt(params.get("percentage")),
                toInt(params.get("bucketStart")),
                toInt(params.get("bucketEnd")),
                params.get("experimentId") == null ? null : params.get("experimentId").toString());
    }

    private static Integer toInt(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.intValue();
        return Integer.parseInt(v.toString().trim());
    }
}
