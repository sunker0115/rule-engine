package com.sstlfsj.rule.eval.internal.repository;

import com.sstlfsj.rule.kernel.internal.codec.RuleVersionRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** 只读 Mapper：rule_version JOIN rule_definition JOIN scene，供倒排索引加载使用。 */
@Mapper
public interface RuleVersionReadMapper {

    /** 加载所有 ACTIVE 状态的规则版本（启动时全量加载）。 */
    @Select("""
            SELECT
              rv.id              AS ruleVersionId,
              s.code             AS sceneCode,
              rd.tenant_id       AS tenantId,
              rv.body            AS bodyJson,
              rv.pre_gates       AS preGatesJson,
              rv.decision_bindings AS decisionBindingsJson,
              rv.trigger_event_types AS triggerEventTypesJson,
              rd.kind            AS kind,
              s.decision_strategy AS decisionStrategy,
              rv.metric_dependencies AS metricDependenciesJson,
              rv.payload_dependencies AS payloadDependenciesJson,
              rd.code            AS code,
              rv.version         AS version,
              s.default_params   AS defaultParamsJson
            FROM rule_version rv
            INNER JOIN rule_definition rd ON rv.rule_definition_id = rd.id
            INNER JOIN scene s ON rd.scene_id = s.id
            WHERE rv.status = 'ACTIVE'
              AND rd.status = 'PUBLISHED'
            """)
    List<RuleVersionRow> loadAllActive();

    /** 加载指定租户 + 场景的所有 ACTIVE 规则版本（热更新时局部刷新）。 */
    @Select("""
            SELECT
              rv.id              AS ruleVersionId,
              s.code             AS sceneCode,
              rd.tenant_id       AS tenantId,
              rv.body            AS bodyJson,
              rv.pre_gates       AS preGatesJson,
              rv.decision_bindings AS decisionBindingsJson,
              rv.trigger_event_types AS triggerEventTypesJson,
              rd.kind            AS kind,
              s.decision_strategy AS decisionStrategy,
              rv.metric_dependencies AS metricDependenciesJson,
              rv.payload_dependencies AS payloadDependenciesJson,
              rd.code            AS code,
              rv.version         AS version,
              s.default_params   AS defaultParamsJson
            FROM rule_version rv
            INNER JOIN rule_definition rd ON rv.rule_definition_id = rd.id
            INNER JOIN scene s ON rd.scene_id = s.id
            WHERE rv.status = 'ACTIVE'
              AND rd.status = 'PUBLISHED'
              AND rd.tenant_id = #{tenantId}
              AND s.code = #{sceneCode}
            """)
    List<RuleVersionRow> loadActiveByScene(@Param("tenantId") Long tenantId,
                                           @Param("sceneCode") String sceneCode);

    /** 按 ruleVersionId 加载单条（dry-run 指定版本时使用，status 不限）。 */
    @Select("""
            SELECT
              rv.id              AS ruleVersionId,
              s.code             AS sceneCode,
              rd.tenant_id       AS tenantId,
              rv.body            AS bodyJson,
              rv.pre_gates       AS preGatesJson,
              rv.decision_bindings AS decisionBindingsJson,
              rv.trigger_event_types AS triggerEventTypesJson,
              rd.kind            AS kind,
              s.decision_strategy AS decisionStrategy,
              rv.metric_dependencies AS metricDependenciesJson,
              rv.payload_dependencies AS payloadDependenciesJson,
              rd.code            AS code,
              rv.version         AS version,
              s.default_params   AS defaultParamsJson
            FROM rule_version rv
            INNER JOIN rule_definition rd ON rv.rule_definition_id = rd.id
            INNER JOIN scene s ON rd.scene_id = s.id
            WHERE rv.id = #{ruleVersionId}
            """)
    RuleVersionRow loadById(@Param("ruleVersionId") Long ruleVersionId);

    /** 按 ruleId 取最新版本 id（最高版本号，含 DRAFT），供 dry-run ruleId 模式解析目标。不存在返回 null。 */
    @Select("""
            SELECT rv.id
            FROM rule_version rv
            INNER JOIN rule_definition rd ON rv.rule_definition_id = rd.id
            WHERE rd.tenant_id = #{tenantId} AND rd.id = #{ruleId}
            ORDER BY rv.version DESC
            LIMIT 1
            """)
    Long latestVersionIdByRule(@Param("tenantId") Long tenantId, @Param("ruleId") Long ruleId);
}
