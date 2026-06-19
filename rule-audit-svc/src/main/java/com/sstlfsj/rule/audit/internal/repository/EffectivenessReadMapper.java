package com.sstlfsj.rule.audit.internal.repository;

import com.sstlfsj.rule.audit.internal.domain.ConfusionCountRow;
import com.sstlfsj.rule.audit.internal.domain.WindowTotalsRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/** 决策效果聚合只读 Mapper（B32）：JSON_TABLE 展开 hit_decisions 出原始计数，比率在 service 算。 */
@Mapper
public interface EffectivenessReadMapper {

    /**
     * 按 (bucket, 维度键) 聚合 TP/FP/firedTotal（去重到 session 粒度，规则绑多决策不重复计）。
     *
     * @param tenantId       租户
     * @param sceneCode      场景
     * @param from           窗口起（含）
     * @param to             窗口止（不含）
     * @param dimension      'RULE_VERSION'（取 $.ruleVersionId）或 'DECISION'（取 $.code）
     * @param positiveLabels positive 标签集（可空/空 → 全部计 negative）
     * @param bucketUnit     'NONE' | 'DAY' | 'WEEK'
     * @return 混淆计数行
     */
    List<ConfusionCountRow> confusionByDimension(
            @Param("tenantId") Long tenantId, @Param("sceneCode") String sceneCode,
            @Param("from") LocalDateTime from, @Param("to") LocalDateTime to,
            @Param("dimension") String dimension, @Param("positiveLabels") List<String> positiveLabels,
            @Param("bucketUnit") String bucketUnit);

    /**
     * 按 bucket 聚合窗口总量（总 session / 有标签 / positive / negative / blocked）。
     *
     * @param tenantId       租户
     * @param sceneCode      场景
     * @param from           窗口起（含）
     * @param to             窗口止（不含）
     * @param positiveLabels positive 标签集（可空/空 → 全部计 negative）
     * @param bucketUnit     'NONE' | 'DAY' | 'WEEK'
     * @return 桶总量行
     */
    List<WindowTotalsRow> windowTotals(
            @Param("tenantId") Long tenantId, @Param("sceneCode") String sceneCode,
            @Param("from") LocalDateTime from, @Param("to") LocalDateTime to,
            @Param("positiveLabels") List<String> positiveLabels, @Param("bucketUnit") String bucketUnit);
}
