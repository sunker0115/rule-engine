package com.sstlfsj.rule.audit.internal.domain;

import lombok.Getter;
import lombok.Setter;

/** 效果聚合的单时间桶窗口总量投影（B32，audit-svc 内部只读）。 */
@Getter
@Setter
public class WindowTotalsRow {
    /** 时间桶（NONE 时为 'ALL'）。 */
    private String bucket;
    /** 桶内 session 总数。 */
    private long totalSessions;
    /** 桶内有标签的 session 数。 */
    private long labeledCount;
    /** 桶内标签为 positive 的 session 数。 */
    private long totalPositive;
    /** 桶内标签为 negative 的 session 数。 */
    private long totalNegative;
    /** 桶内 BLOCKED 状态的 session 数（reject inference 残缺面）。 */
    private long blockedCount;
}
