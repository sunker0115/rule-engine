package com.sstlfsj.rule.kernel.api.model.ast;

import java.util.List;

/**
 * DECISION_TABLE 根节点：按行顺序匹配，第一条满足所有列条件的行胜出（FIRST_HIT 语义）。
 * conditionValue 为 null 表示通配（该列任意值均满足）。
 */
public record DecisionTableNode(
        List<Column> columns,
        List<Row> rows
) implements AstNode {

    /**
     * 列定义：指标编码 + 操作符（与 ConditionEvaluator 的 conditionType 对应）+ 冻结的 dataType。
     * dataType 在发布期由 {@code AstDataTypeResolver} 从 metricCode 冻结；草稿期为 null
     * （求值期合成节点的 dataType 即为 null → {@code ComparisonStrategyFactory} 走 Default 策略）。
     */
    public record Column(String metricCode, String operator, String dataType) {
        /** 草稿期便利构造：dataType 未冻结（null）。与 ConditionNode 的草稿构造同源。 */
        public Column(String metricCode, String operator) {
            this(metricCode, operator, null);
        }
    }

    /**
     * 行定义：与 columns 等长的条件值列表 + 命中决策码。
     * conditions[i] 对应 columns[i]；null 表示通配。
     */
    public record Row(List<Object> conditions, String decisionCode) {}
}
