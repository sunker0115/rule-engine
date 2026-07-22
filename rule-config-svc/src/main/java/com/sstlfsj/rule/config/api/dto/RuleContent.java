package com.sstlfsj.rule.config.api.dto;

import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot.DecisionBinding;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot.PreGateConfig;
import com.sstlfsj.rule.kernel.api.model.ScriptSource;
import com.sstlfsj.rule.kernel.api.model.ast.AstNode;
import com.sstlfsj.rule.kernel.api.model.flow.FlowGraph;

import java.util.List;

/**
 * 规则写内容载体：createDraft/editDraft/newVersion 共有的内容字段。
 * 新增规则内容字段只改本 record + controller 的 DTO 映射，不动三方法签名与调用点。
 *
 * @param name              规则名称
 * @param kind              规则类型（AST_BOOLEAN/SCORECARD/DECISION_TREE/DECISION_TABLE/EXPRESSION_SCRIPT/DECISION_FLOW）
 * @param conditionAst      条件 AST
 * @param decisionBindings  决策绑定列表（草稿期 priority 占位，发布时回填）
 * @param preGates          前置门控列表
 * @param triggerEventTypes 触发事件类型列表
 * @param script            EXPRESSION_SCRIPT 脚本载体，其它 kind 传 null
 * @param flowGraph         DECISION_FLOW 决策图，其它 kind 传 null
 */
public record RuleContent(
        String name, String kind,
        AstNode conditionAst,
        List<DecisionBinding> decisionBindings,
        List<PreGateConfig> preGates,
        List<String> triggerEventTypes,
        ScriptSource script,
        FlowGraph flowGraph) {}
