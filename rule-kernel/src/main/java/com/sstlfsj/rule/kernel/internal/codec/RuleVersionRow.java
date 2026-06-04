package com.sstlfsj.rule.kernel.internal.codec;

/**
 * 数据库 JOIN 查询结果行，供 SnapshotAssembler 装配 RuleVersionSnapshot 使用。
 * 纯数据载体，无 Spring 依赖。
 * {@code decisionStrategy} 来自 scene 表，场景级字段，供 SceneSnapshotLoader 写入 SceneRuleIndex。
 */
public record RuleVersionRow(
        Long ruleVersionId,
        String sceneCode,
        Long tenantId,
        String conditionAstJson,
        String preGatesJson,
        String decisionBindingsJson,
        String triggerEventTypesJson,
        String kind,
        String decisionStrategy
) {}
