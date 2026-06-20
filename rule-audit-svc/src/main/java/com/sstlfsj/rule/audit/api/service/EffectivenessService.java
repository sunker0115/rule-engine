package com.sstlfsj.rule.audit.api.service;

import java.time.Instant;
import java.util.List;

/** 决策效果聚合查询（B32）：按规则版本 / Decision 维度算混淆矩阵 + precision/recall + 漂移。 */
public interface EffectivenessService {

    /** 聚合维度。 */
    enum Dimension { RULE_VERSION, DECISION }

    /** 时间分桶（漂移序列）。 */
    enum Bucket { NONE, DAY, WEEK }

    /**
     * 聚合查询条件。
     *
     * @param tenantId       租户
     * @param sceneCode      场景（recall 的 FN 分母作用域）
     * @param from           窗口起（含），按 evaluation_session.occurred_at
     * @param to             窗口止（不含）
     * @param positiveLabels positive 判定口径（业务给）；空则全部计为 negative
     * @param dimension      聚合维度
     * @param bucket         时间分桶
     */
    record EffectivenessQuery(Long tenantId, String sceneCode, Instant from, Instant to,
                              List<String> positiveLabels, Dimension dimension, Bucket bucket) {}

    /** 单维度键的混淆矩阵 + 指标。precision/recall 分母为 0 时为 null。 */
    record EffectivenessRow(String dimensionKey, long tp, long fp, long fn, long tn,
                            Double precision, Double recall, double fireRate, long firedTotal) {}

    /** 单时间桶的报表：含诚实回报口径（unlabeled / blocked 不入指标分母）。 */
    record BucketReport(String bucket, long totalSessions, long labeledCount, long unlabeledCount,
                        long blockedCount, long totalPositive, long totalNegative,
                        List<EffectivenessRow> rows) {}

    /** 聚合报表：按桶分组（NONE 时单桶 bucket=null）。 */
    record EffectivenessReport(List<BucketReport> buckets) {}

    /**
     * 按需聚合决策效果。
     *
     * @param q 查询条件
     * @return 分桶报表
     */
    EffectivenessReport aggregate(EffectivenessQuery q);
}
