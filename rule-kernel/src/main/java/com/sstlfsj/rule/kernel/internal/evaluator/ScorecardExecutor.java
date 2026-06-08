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

        // collect=false 时跳过 NodeTrace 构建（traces 保持空），score/decision/hit 不受影响
        boolean collect = TraceScope.COLLECT.orElse(true);
        // collect=false 时不分配，遵循「trace 关闭即零分配」契约
        List<NodeTrace> factorTraces = collect ? new ArrayList<>() : null;
        double score = 0.0;
        Long rvId = snapshot.ruleVersionId();

        for (ConditionNode node : root.conditions()) {
            ConditionOutcome outcome = ConditionEvaluation.evaluate(node, ctx, evaluators);
            if (outcome.isError()) {
                // 风控保守：任一条件取数失败/无算子 → 整卡置 ERROR 不出分，避免漏分误判
                if (collect) {
                    factorTraces.add(new NodeTrace("ConditionNode", node.conditionType(), node.metricCode(),
                            false, outcome.resolvedValue(), outcome.valueSource(), outcome.errorCode(), List.of(), rvId));
                }
                return new EvalResult(false, null, List.of(),
                        scorecardRoot(collect, false, factorTraces, rvId),
                        outcome.errorCode(), List.of(), null, null, null);
            }
            boolean met = outcome.satisfied();
            if (met && node.weight() != null) {
                score += node.weight();
            }
            if (collect) {
                factorTraces.add(new NodeTrace("ConditionNode", node.conditionType(), node.metricCode(),
                        met, outcome.resolvedValue(), outcome.valueSource(), null, List.of(), rvId));
            }
        }

        boolean hit = score >= root.threshold();
        return new EvalResult(hit, null, List.of(),
                scorecardRoot(collect, hit, factorTraces, rvId),
                null, List.of(), score, null, null);
    }

    /**
     * 将各因子 trace 包进单一 ScorecardRoot 根节点；非收集模式返回空列表。
     *
     * @param collect 是否收集 trace
     * @param result  评分卡整体命中结果
     * @param factors 各因子（ConditionNode）trace
     * @param rvId    规则版本 ID
     * @return 含单个 ScorecardRoot 的列表，或空列表
     */
    private static List<NodeTrace> scorecardRoot(boolean collect, boolean result, List<NodeTrace> factors, Long rvId) {
        if (!collect) return List.of();
        return List.of(new NodeTrace("ScorecardRoot", null, null, result, null, null, null, factors, rvId));
    }
}
