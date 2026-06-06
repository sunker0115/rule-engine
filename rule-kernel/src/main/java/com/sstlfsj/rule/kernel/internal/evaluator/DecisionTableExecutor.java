package com.sstlfsj.rule.kernel.internal.evaluator;

import com.sstlfsj.rule.kernel.api.model.*;
import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;
import com.sstlfsj.rule.kernel.api.model.ast.DecisionTableNode;
import com.sstlfsj.rule.kernel.api.spi.condition.ConditionEvaluator;
import com.sstlfsj.rule.kernel.api.spi.executor.RuleVersionExecutor;

import java.util.List;
import java.util.Map;

/**
 * DECISION_TABLE evaluator：按行顺序匹配，第一条所有列满足的行胜出（FIRST_HIT 语义）。
 * 列条件 null 表示通配，任意值均满足。
 */
public class DecisionTableExecutor implements RuleVersionExecutor {

    private final Map<String, ConditionEvaluator> evaluators;

    /**
     * @param evaluators conditionType 到 ConditionEvaluator 的映射
     */
    public DecisionTableExecutor(Map<String, ConditionEvaluator> evaluators) {
        this.evaluators = Map.copyOf(evaluators);
    }

    @Override
    public EvalResult execute(RuleVersionSnapshot snapshot, EvalContext ctx) {
        if (!(snapshot.conditionAst() instanceof DecisionTableNode table)) {
            return new EvalResult(false, null, List.of(), List.of(),
                    "DECISION_TABLE_AST_TYPE_MISMATCH", List.of(), null, null, null);
        }

        List<DecisionTableNode.Column> columns = table.columns();

        for (DecisionTableNode.Row row : table.rows()) {
            RowResult rr = rowMatches(row, columns, ctx);
            if (rr.error() != null) {
                // 取数失败：不静默落下一行，整表置 ERROR + miss
                return new EvalResult(false, null, List.of(), List.of(),
                        rr.error(), List.of(), null, null, null);
            }
            if (rr.matched()) {
                return hit(row.decisionCode(), snapshot);
            }
        }
        return EvalResult.miss();
    }

    /** 行匹配结果：matched=该行是否全列满足；error 非 null 表示取数失败（中止整表）。 */
    private record RowResult(boolean matched, String error) {}

    private RowResult rowMatches(DecisionTableNode.Row row,
                                 List<DecisionTableNode.Column> columns,
                                 EvalContext ctx) {
        List<Object> conditions = row.conditions();
        for (int i = 0; i < columns.size(); i++) {
            Object condValue = (i < conditions.size()) ? conditions.get(i) : null;
            if (condValue == null) continue; // null 表示通配

            DecisionTableNode.Column col = columns.get(i);
            // 将列的 condValue 包装为 ConditionNode params，复用现有 ConditionEvaluator 约定
            Map<String, Object> params = buildParams(col.operator(), condValue);
            // 传入冻结的列 dataType（B22）：令 ComparisonStrategyFactory 按声明类型路由而非运行时猜测；
            // 草稿/未冻结时 dataType=null，退化为 Default 策略（与历史行为一致）
            ConditionNode node = new ConditionNode(col.operator(), col.metricCode(), null, params, 0.0, col.dataType());
            ConditionOutcome o = ConditionEvaluation.evaluate(node, ctx, evaluators);
            if (o.isError()) return new RowResult(false, o.errorCode());
            if (!o.satisfied()) return new RowResult(false, null); // 本行不匹配
        }
        return new RowResult(true, null);
    }

    /**
     * 将行中的条件值转成 ConditionNode.params，遵循各算子约定：
     * 数值类算子用 "threshold"，IN 类算子用 "values"。
     */
    private static Map<String, Object> buildParams(String operator, Object condValue) {
        return switch (operator.toUpperCase()) {
            case "IN", "NOT_IN" -> Map.of("values", condValue);
            default             -> Map.of("threshold", condValue);
        };
    }

    private EvalResult hit(String decisionCode, RuleVersionSnapshot snapshot) {
        Decision decision = snapshot.decisionBindings().stream()
                .filter(b -> b.decisionCode().equals(decisionCode))
                .max(java.util.Comparator.comparingInt(RuleVersionSnapshot.DecisionBinding::priority))
                .map(b -> new Decision(b.decisionCode(), "", b.priority(), snapshot.ruleVersionId()))
                .orElseGet(() -> new Decision(decisionCode, "", 0, snapshot.ruleVersionId()));
        return new EvalResult(true, decision, List.of(decision),
                List.of(), null, List.of(), null, null, decisionCode);
    }
}
