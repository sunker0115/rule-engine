package com.sstlfsj.rule.config.api.dto;

/** 规则详情：规则定义基本信息 + 当前 ACTIVE 版本的条件 AST 与决策绑定，供前端编辑回填。 */
public record RuleDetailVO(
        Long ruleDefinitionId, String code, String name, String status, String kind,
        String sceneCode, Object conditionAst, Object decisionBindings, Long currentVersionId) {}
