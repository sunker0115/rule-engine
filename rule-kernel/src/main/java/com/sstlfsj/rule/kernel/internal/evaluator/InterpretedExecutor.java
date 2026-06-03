package com.sstlfsj.rule.kernel.internal.evaluator;

import com.sstlfsj.rule.kernel.api.model.EvalContext;
import com.sstlfsj.rule.kernel.api.model.EvalResult;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;
import com.sstlfsj.rule.kernel.api.model.ast.AndNode;
import com.sstlfsj.rule.kernel.api.model.ast.AstNode;
import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;
import com.sstlfsj.rule.kernel.api.model.ast.NotNode;
import com.sstlfsj.rule.kernel.api.model.ast.OrNode;
import com.sstlfsj.rule.kernel.api.spi.condition.ConditionEvaluator;
import com.sstlfsj.rule.kernel.api.spi.executor.RuleVersionExecutor;

import java.util.Map;

/** 使用已注册的 ConditionEvaluator 对 RuleVersionSnapshot AST 树进行解释执行。 */
public class InterpretedExecutor implements RuleVersionExecutor {

    private final Map<String, ConditionEvaluator> evaluators;

    public InterpretedExecutor(Map<String, ConditionEvaluator> evaluators) {
        this.evaluators = Map.copyOf(evaluators);
    }

    @Override
    public EvalResult execute(RuleVersionSnapshot snapshot, EvalContext ctx) {
        boolean satisfied = evaluate(snapshot.conditionAst(), ctx);
        return satisfied ? EvalResult.hit() : EvalResult.miss();
    }

    private boolean evaluate(AstNode node, EvalContext ctx) {
        return switch (node) {
            case AndNode and     -> evaluateAnd(and, ctx);
            case OrNode or       -> evaluateOr(or, ctx);
            case NotNode not     -> !evaluate(not.child(), ctx);
            case ConditionNode c -> evaluateCondition(c, ctx);
        };
    }

    private boolean evaluateAnd(AndNode and, EvalContext ctx) {
        for (AstNode child : and.children()) {
            if (!evaluate(child, ctx)) return false; // 短路：任一子节点为 false 即返回
        }
        return true;
    }

    private boolean evaluateOr(OrNode or, EvalContext ctx) {
        for (AstNode child : or.children()) {
            if (evaluate(child, ctx)) return true; // 短路：任一子节点为 true 即返回
        }
        return false;
    }

    private boolean evaluateCondition(ConditionNode node, EvalContext ctx) {
        ConditionEvaluator evaluator = evaluators.get(node.conditionType());
        if (evaluator == null) {
            throw new IllegalStateException(
                    "No ConditionEvaluator registered for type: " + node.conditionType());
        }
        return evaluator.evaluate(node, ctx);
    }
}
