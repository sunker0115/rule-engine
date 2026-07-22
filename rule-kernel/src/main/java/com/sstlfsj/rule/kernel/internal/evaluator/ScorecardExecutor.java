package com.sstlfsj.rule.kernel.internal.evaluator;

import com.sstlfsj.rule.kernel.api.model.Decision;
import com.sstlfsj.rule.kernel.api.model.EvalContext;
import com.sstlfsj.rule.kernel.api.model.EvalErrorCode;
import com.sstlfsj.rule.kernel.api.model.EvalResult;
import com.sstlfsj.rule.kernel.api.model.NodeTrace;
import com.sstlfsj.rule.kernel.api.model.NodeType;
import com.sstlfsj.rule.kernel.api.model.AstBody;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;
import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;
import com.sstlfsj.rule.kernel.api.model.ast.ScoreBand;
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
        if (!(snapshot.body() instanceof AstBody(ScorecardRootNode root))) {
            return EvalResult.error(EvalErrorCode.SCORECARD_AST_TYPE_MISMATCH);
        }

        // collect=false 时跳过 NodeTrace 构建（traces 保持空），score/decision/hit 不受影响
        boolean collect = TraceScope.COLLECT.orElse(true);
        // collect=false 时不分配，遵循「trace 关闭即零分配」契约
        List<NodeTrace> factorTraces = collect ? new ArrayList<>() : null;
        double score = 0.0;
        Long rvId = snapshot.ruleVersionId();
        String code = snapshot.code();
        long version = snapshot.version();

        for (ConditionNode node : root.conditions()) {
            ConditionOutcome outcome = ConditionEvaluation.evaluate(node, ctx, evaluators);
            if (outcome.isError()) {
                // 风控保守：任一条件取数失败/无算子 → 整卡置 ERROR 不出分，避免漏分误判
                if (collect) {
                    factorTraces.add(new NodeTrace(NodeType.CONDITION.tag(), node.conditionType(), node.metricCode(),
                            false, outcome.resolvedValue(), outcome.valueSource(), outcome.errorCode(), List.of(), rvId,
                            code, version, node.params(), node.displayLabel()));
                }
                return EvalResult.error(outcome.errorCode(),
                        scorecardRoot(collect, false, factorTraces, rvId, code, version));
            }
            boolean met = outcome.satisfied();
            if (met && node.weight() != null) {
                score += node.weight();
            }
            if (collect) {
                factorTraces.add(new NodeTrace(NodeType.CONDITION.tag(), node.conditionType(), node.metricCode(),
                        met, outcome.resolvedValue(), outcome.valueSource(), null, List.of(), rvId,
                        code, version, node.params(), node.displayLabel()));
            }
        }

        if (root.bands().isEmpty()) {
            // 现状路径：单 threshold，无 decision（行为一字不动）
            boolean hit = score >= root.threshold();
            return new EvalResult(hit, null, List.of(),
                    scorecardRoot(collect, hit, factorTraces, rvId, code, version),
                    null, score, null, null);
        }
        // bands 非空：threshold 作命中门槛，score < threshold 则弃权
        if (score < root.threshold()) {
            return new EvalResult(false, null, List.of(),
                    scorecardRoot(collect, false, factorTraces, rvId, code, version),
                    null, score, null, null);
        }
        // 命中门槛：找 score ∈ [minScore, maxScore) 的段
        ScoreBand band = null;
        for (ScoreBand b : root.bands()) {
            if (score >= b.minScore() && score < b.maxScore()) { band = b; break; }
        }
        if (band == null) {
            // 落空隙：命中但无段决策（回退 EvalEngine binding）
            return new EvalResult(true, null, List.of(),
                    scorecardRoot(collect, true, factorTraces, rvId, code, version),
                    null, score, null, null);
        }
        // 段命中：出决策。ScoreBand 已含发布期回填的 name/priority，直接读，不再索引
        // decisionBindings（评分卡语义内聚）。旧快照（bands 无 name/priority）兜底：name=""
        // priority=0，与原 orElseGet 回退行为一致。
        ScoreBand hitBand = band;
        Decision decision = new Decision(hitBand.decisionCode(),
                hitBand.name() != null ? hitBand.name() : "",
                hitBand.priority(),
                snapshot.ruleVersionId(), snapshot.code(), snapshot.version(), hitBand.category());
        return new EvalResult(true, decision, List.of(decision),
                scorecardRoot(collect, true, factorTraces, rvId, code, version),
                null, score, band.category(), null);
    }

    /**
     * 将各因子 trace 包进单一 ScorecardRoot 根节点；非收集模式返回空列表。
     *
     * @param collect 是否收集 trace
     * @param result  评分卡整体命中结果
     * @param factors 各因子（ConditionNode）trace
     * @param rvId    规则版本 ID
     * @param code    规则逻辑编码
     * @param version 规则版本号
     * @return 含单个 ScorecardRoot 的列表，或空列表
     */
    private static List<NodeTrace> scorecardRoot(boolean collect, boolean result, List<NodeTrace> factors,
                                                 Long rvId, String code, long version) {
        if (!collect) return List.of();
        return List.of(NodeTrace.container(NodeType.SCORECARD_ROOT, result, factors, rvId, code, version));
    }
}
