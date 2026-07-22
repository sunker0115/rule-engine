package com.sstlfsj.rule.config.api.dto;

import com.sstlfsj.rule.kernel.api.model.RuleBody;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot.DecisionBinding;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot.PreGateConfig;

import java.util.List;

/**
 * 规则写内容载体：createDraft/editDraft/newVersion 共有的内容字段。
 * 新增规则内容字段只改本 record + controller 的 DTO 映射，不动三方法签名与调用点。
 *
 * @param name              规则名称
 * @param kind              规则类型（AST_BOOLEAN/SCORECARD/DECISION_TREE/DECISION_TABLE/EXPRESSION_SCRIPT/DECISION_FLOW）
 * @param body              判定主体多态载体（AstBody/ScriptBody/FlowBody，与 kind 一致）
 * @param decisionBindings  决策绑定列表（草稿期 priority 占位，发布时回填）
 * @param preGates          前置门控列表
 * @param triggerEventTypes 触发事件类型列表
 */
public record RuleContent(
        String name, String kind,
        RuleBody body,
        List<DecisionBinding> decisionBindings,
        List<PreGateConfig> preGates,
        List<String> triggerEventTypes) {}
