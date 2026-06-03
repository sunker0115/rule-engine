package com.sstlfsj.rule.eval.internal.snapshot;

/**
 * rule_version JOIN rule_definition JOIN scene 的 JOIN 查询结果 DTO。
 * 字段均为原始 JSON 字符串，由 SnapshotAssembler 反序列化为域对象。
 */
public record RuleVersionRow(
        Long ruleVersionId,
        String sceneCode,
        Long tenantId,
        String conditionAstJson,
        String preGatesJson,
        String decisionBindingsJson,
        String triggerEventTypesJson
) {}
