package com.sstlfsj.rule.kernel.internal.evaluator;

import com.sstlfsj.rule.kernel.api.model.*;
import com.sstlfsj.rule.kernel.api.model.ast.*;
import com.sstlfsj.rule.kernel.api.spi.condition.ConditionEvaluator;
import com.sstlfsj.rule.kernel.api.spi.executor.RuleVersionExecutor;

import java.util.List;
import java.util.Map;

/**
 * DECISION_TREE evaluator：递归遍历 IfNode 树，命中 DecisionLeafNode 时返回决策结果。
 */
public class DecisionTreeExecutor implements RuleVersionExecutor {

    private final Map<String, ConditionEvaluator> evaluators;

    /**
     * @param evaluators conditionType 到 ConditionEvaluator 的映射，用于 IfNode 条件求值
     */
    public DecisionTreeExecutor(Map<String, ConditionEvaluator> evaluators) {
        this.evaluators = Map.copyOf(evaluators);
    }

    @Override
    public EvalResult execute(RuleVersionSnapshot snapshot, EvalContext ctx) {
        if (!(snapshot.conditionAst() instanceof IfNode root)) {
            return new EvalResult(false, null, List.of(), List.of(),
                    "DECISION_TREE_AST_TYPE_MISMATCH", List.of(), null, null, null);
        }
        return evaluate(root, snapshot, ctx);
    }

    private EvalResult evaluate(AstNode node, RuleVersionSnapshot snapshot, EvalContext ctx) {
        return switch (node) {
            case IfNode ifNode -> evaluateIf(ifNode, snapshot, ctx);
            case DecisionLeafNode leaf -> hit(leaf, snapshot);
            default -> new EvalResult(false, null, List.of(), List.of(),
                    "DECISION_TREE_UNEXPECTED_NODE", List.of(), null, null, null);
        };
    }

    private EvalResult evaluateIf(IfNode ifNode, RuleVersionSnapshot snapshot, EvalContext ctx) {
        ConditionOutcome cond = evaluateCondition(ifNode.condition(), ctx);
        if (cond.isError()) {
            // 取数失败：不静默走 else，整规则置 ERROR + miss（避免命中错误叶子）
            return new EvalResult(false, null, List.of(), List.of(),
                    cond.errorCode(), List.of(), null, null, null);
        }
        if (cond.satisfied()) {
            return evaluate(ifNode.thenBranch(), snapshot, ctx);
        } else if (ifNode.elseBranch() != null) {
            return evaluate(ifNode.elseBranch(), snapshot, ctx);
        } else {
            return EvalResult.miss();
        }
    }

    private ConditionOutcome evaluateCondition(AstNode node, EvalContext ctx) {
        return switch (node) {
            case ConditionNode c -> ConditionEvaluation.evaluate(c, ctx, evaluators);
            case AndNode and -> {
                for (AstNode child : and.children()) {
                    ConditionOutcome o = evaluateCondition(child, ctx);
                    if (o.isError()) yield o;                       // ERROR 传播
                    if (!o.satisfied()) yield ConditionOutcome.NOT_SATISFIED; // 短路 false
                }
                yield ConditionOutcome.SATISFIED;
            }
            case OrNode or -> {
                String errCode = null;
                for (AstNode child : or.children()) {
                    ConditionOutcome o = evaluateCondition(child, ctx);
                    if (o.satisfied()) yield ConditionOutcome.SATISFIED; // 命中即短路，不在意其它
                    if (o.isError()) errCode = o.errorCode();
                }
                // 全不满足；若曾有 ERROR 则整体不可判定（保守）
                yield errCode != null ? ConditionOutcome.error(errCode) : ConditionOutcome.NOT_SATISFIED;
            }
            case NotNode not -> {
                ConditionOutcome o = evaluateCondition(not.child(), ctx);
                yield o.isError() ? o : ConditionOutcome.of(!o.satisfied());
            }
            default -> ConditionOutcome.error(ConditionEvaluation.NO_EVALUATOR);
        };
    }

    private EvalResult hit(DecisionLeafNode leaf, RuleVersionSnapshot snapshot) {
        Decision decision = snapshot.decisionBindings().stream()
                .filter(b -> b.decisionCode().equals(leaf.decisionCode()))
                .max(java.util.Comparator.comparingInt(RuleVersionSnapshot.DecisionBinding::priority))
                .map(b -> new Decision(b.decisionCode(), "", b.priority(), snapshot.ruleVersionId()))
                .orElseGet(() -> new Decision(leaf.decisionCode(), "", 0, snapshot.ruleVersionId()));
        return new EvalResult(true, decision, List.of(decision),
                List.of(), null, List.of(), null, leaf.category(), null);
    }
}
