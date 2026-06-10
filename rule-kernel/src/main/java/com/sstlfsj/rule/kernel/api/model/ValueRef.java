package com.sstlfsj.rule.kernel.api.model;

/** ConditionNode 值引用来源:METRIC=受治理指标(走 ctx.metrics 取数/注入);PAYLOAD=事件自带字段(直接读 event.payload)。 */
public enum ValueRef {
    METRIC, PAYLOAD;

    /** 序列化标签(== 枚举名)。 */
    public String tag() {
        return name();
    }
}
