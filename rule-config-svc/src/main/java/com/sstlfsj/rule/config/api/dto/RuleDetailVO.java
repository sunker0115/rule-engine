package com.sstlfsj.rule.config.api.dto;

import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot.DecisionBinding;
import com.sstlfsj.rule.kernel.api.model.ast.AstNode;

import java.util.List;

/** 规则详情：规则定义基本信息 + 当前 ACTIVE 版本的条件 AST 与决策绑定，供前端编辑回填。 */
public record RuleDetailVO(
        Long ruleDefinitionId, String code, String name, String status, String kind,
        String sceneCode, AstNode conditionAst, List<DecisionBinding> decisionBindings,
        Long currentVersionId) {}
