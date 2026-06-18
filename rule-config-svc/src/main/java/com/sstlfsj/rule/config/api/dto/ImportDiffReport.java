package com.sstlfsj.rule.config.api.dto;

import java.util.List;

/**
 * Bundle import 的 diff 报告，由 dry-run 或 apply 返回。
 *
 * <ul>
 *   <li>{@link #willCreate}：将新建（目标不存在同 code 规则）。</li>
 *   <li>{@link #willOverwrite}：将覆盖（OVERWRITE 策略且目标有 DRAFT）。</li>
 *   <li>{@link #skipped}：已跳过（SKIP 策略且目标已存在；或 hash 完全一致无需变更）。</li>
 *   <li>{@link #conflicts}：冲突（ABORT 策略下收集的全部冲突；apply 成功后此列表为空）。</li>
 *   <li>{@link #metricsSkipped}：未自动导入的 metric 明细（已存在 / sourceType 不支持，如 SQL_AGGREGATE 需人工处理）。</li>
 * </ul>
 */
public record ImportDiffReport(
        List<RuleImportItem> willCreate,
        List<RuleImportItem> willOverwrite,
        List<RuleImportItem> skipped,
        List<RuleImportConflict> conflicts,
        int scenesCreated,
        int metricsCreated,
        List<MetricImportItem> metricsSkipped,
        int decisionsCreated
) {
    /** 将被新建或覆盖/跳过的规则条目。 */
    public record RuleImportItem(
            String ruleCode,
            String sceneCode,
            /** 操作原因描述，如"目标不存在新建"/"hash 一致跳过"等。 */
            String reason
    ) {}

    /** 冲突条目（ABORT 模式下收集）。 */
    public record RuleImportConflict(
            String ruleCode,
            String sceneCode,
            /** 冲突类型：EXISTING_ACTIVE / EXISTING_DRAFT / CONTENT_CHANGED 等。 */
            String conflictType,
            String detail
    ) {}

    /** 未自动导入的 metric 明细。 */
    public record MetricImportItem(
            String metricCode,
            /** 跳过原因，如"目标已存在"/"sourceType 不支持自动导入"。 */
            String reason
    ) {}
}
