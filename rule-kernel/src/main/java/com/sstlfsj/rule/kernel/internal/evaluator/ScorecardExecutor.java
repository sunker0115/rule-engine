package com.sstlfsj.rule.kernel.internal.evaluator;

import com.sstlfsj.rule.kernel.api.model.EvalContext;
import com.sstlfsj.rule.kernel.api.model.EvalResult;
import com.sstlfsj.rule.kernel.api.model.NodeTrace;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;
import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;
import com.sstlfsj.rule.kernel.api.model.ast.ScorecardRootNode;
import com.sstlfsj.rule.kernel.api.spi.condition.ConditionEvaluator;
import com.sstlfsj.rule.kernel.api.spi.executor.RuleVersionExecutor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 评分卡 evaluator（D12 SCORECARD kind）。
 * 遍历所有叶子条件（不短路），满足则累加 weight，score >= threshold 时 ruleHit=true。
 */
public class ScorecardExecutor implements RuleVersionExecutor {

    private final Map<String, ConditionEvaluator> evaluators;

    /**
     * @param evaluators conditionType 到 ConditionEvaluator 的映射
     */
    public ScorecardExecutor(Map<String, ConditionEvaluator> evaluators) {
        this.evaluators = Map.copyOf(evaluators);
    }

    @Override
    public EvalResult execute(RuleVersionSnapshot snapshot, EvalContext ctx) {
        if (!(snapshot.conditionAst() instanceof ScorecardRootNode root)) {
            return new EvalResult(false, null, List.of(), List.of(),
                    "SCORECARD_AST_TYPE_MISMATCH", List.of(), null, null, null);
        }

        List<NodeTrace> traces = new ArrayList<>();
        double score = 0.0;
        Long rvId = snapshot.ruleVersionId();

        for (ConditionNode node : root.conditions()) {
            ConditionOutcome outcome = ConditionEvaluation.evaluate(node, ctx, evaluators);
            if (outcome.isError()) {
                // 风控保守：任一条件取数失败/无算子 → 整卡置 ERROR 不出分，避免漏分误判
                traces.add(new NodeTrace("ConditionNode", node.conditionType(), node.metricCode(),
                        false, null, null, outcome.errorCode(), List.of(), rvId));
                return new EvalResult(false, null, List.of(), traces,
                        outcome.errorCode(), List.of(), null, null, null);
            }
            boolean met = outcome.satisfied();
            if (met && node.weight() != null) {
                score += node.weight();
            }
            traces.add(new NodeTrace("ConditionNode", node.conditionType(), node.metricCode(),
                    met, null, null, null, List.of(), rvId));
        }

        boolean hit = score >= root.threshold();
        return new EvalResult(hit, null, List.of(), traces, null, List.of(), score, null, null);
    }
}
