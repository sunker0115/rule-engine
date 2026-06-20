package com.sstlfsj.rule.audit.internal.domain;

import lombok.Getter;
import lombok.Setter;

/** 效果聚合的单维度键混淆计数投影（B32，audit-svc 内部只读）。 */
@Getter
@Setter
public class ConfusionCountRow {
    /** 时间桶（NONE 时为 'ALL'）。 */
    private String bucket;
    /** 维度键：规则版本 id 或 decision code。 */
    private String dimKey;
    /** 命中且标签为 positive 的 session 数。 */
    private long tp;
    /** 命中且标签为 negative 的 session 数。 */
    private long fp;
    /** 命中本维度键的 session 总数（含未标签）。 */
    private long firedTotal;
}
