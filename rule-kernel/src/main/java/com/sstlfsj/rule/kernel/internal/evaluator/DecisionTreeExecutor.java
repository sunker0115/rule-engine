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
        boolean condResult = evaluateCondition(ifNode.condition(), ctx);
        if (condResult) {
            return evaluate(ifNode.thenBranch(), snapshot, ctx);
        } else if (ifNode.elseBranch() != null) {
            return evaluate(ifNode.elseBranch(), snapshot, ctx);
        } else {
            return EvalResult.miss();
        }
    }

    private boolean evaluateCondition(AstNode node, EvalContext ctx) {
        return switch (node) {
            case ConditionNode c -> {
                ConditionEvaluator ev = evaluators.get(c.conditionType());
                yield ev != null && ev.evaluate(c, ctx);
            }
            case AndNode and -> {
                for (AstNode child : and.children()) {
                    if (!evaluateCondition(child, ctx)) yield false;
                }
                yield true;
            }
            case OrNode or -> {
                for (AstNode child : or.children()) {
                    if (evaluateCondition(child, ctx)) yield true;
                }
                yield false;
            }
            case NotNode not -> !evaluateCondition(not.child(), ctx);
            default -> false;
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
