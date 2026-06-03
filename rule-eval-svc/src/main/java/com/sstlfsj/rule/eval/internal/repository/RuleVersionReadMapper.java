package com.sstlfsj.rule.eval.internal.repository;

import com.sstlfsj.rule.eval.internal.snapshot.RuleVersionRow;
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
              rv.condition_ast   AS conditionAstJson,
              rv.pre_gates       AS preGatesJson,
              rv.decision_bindings AS decisionBindingsJson,
              rv.trigger_event_types AS triggerEventTypesJson
            FROM rule_version rv
            INNER JOIN rule_definition rd ON rv.rule_definition_id = rd.id
            INNER JOIN scene s ON rd.scene_id = s.id
            WHERE rv.status = 'ACTIVE'
            """)
    List<RuleVersionRow> loadAllActive();

    /** 加载指定租户 + 场景的所有 ACTIVE 规则版本（热更新时局部刷新）。 */
    @Select("""
            SELECT
              rv.id              AS ruleVersionId,
              s.code             AS sceneCode,
              rd.tenant_id       AS tenantId,
              rv.condition_ast   AS conditionAstJson,
              rv.pre_gates       AS preGatesJson,
              rv.decision_bindings AS decisionBindingsJson,
              rv.trigger_event_types AS triggerEventTypesJson
            FROM rule_version rv
            INNER JOIN rule_definition rd ON rv.rule_definition_id = rd.id
            INNER JOIN scene s ON rd.scene_id = s.id
            WHERE rv.status = 'ACTIVE'
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
              rv.condition_ast   AS conditionAstJson,
              rv.pre_gates       AS preGatesJson,
              rv.decision_bindings AS decisionBindingsJson,
              rv.trigger_event_types AS triggerEventTypesJson
            FROM rule_version rv
            INNER JOIN rule_definition rd ON rv.rule_definition_id = rd.id
            INNER JOIN scene s ON rd.scene_id = s.id
            WHERE rv.id = #{ruleVersionId}
            """)
    RuleVersionRow loadById(@Param("ruleVersionId") Long ruleVersionId);
}
