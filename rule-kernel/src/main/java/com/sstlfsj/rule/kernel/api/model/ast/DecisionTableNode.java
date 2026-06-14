package com.sstlfsj.rule.kernel.api.model.ast;

import com.sstlfsj.rule.kernel.api.model.ValueRef;

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
     * 列定义：引用源（metricCode / payload 字段名） + 操作符 + 取值类型标签。
     * <p>
     * valueRef 为 null 或 METRIC 时 metricCode 是受治理指标编码（发布期冻结校验）；
     * valueRef 为 PAYLOAD 时 metricCode 是 payload 字段名（走 scene.payloadSchema 声明校验）。
     * </p>
     *
     * @param metricCode 指标编码 或 payload 字段名
     * @param operator   条件算子（与 ConditionEvaluator 的 conditionType 对应）
     * @param dataType   冻结的 dataType；草稿期为 null（求值期走 Default 策略）
     * @param valueRef   取值来源；null 或 METRIC=指标，PAYLOAD=payload 字段
     */
    public record Column(String metricCode, String operator, String dataType, ValueRef valueRef) {
        /** 草稿期便利构造：dataType 未冻结，valueRef 默认 METRIC。 */
        public Column(String metricCode, String operator) {
            this(metricCode, operator, null, null);
        }

        /** 含 dataType 的便利构造（兼容旧调用方），valueRef 默认 METRIC。 */
        public Column(String metricCode, String operator, String dataType) {
            this(metricCode, operator, dataType, null);
        }
    }

    /**
     * 行定义：与 columns 等长的条件值列表 + 命中决策码。
     * conditions[i] 对应 columns[i]；null 表示通配。
     */
    public record Row(List<Object> conditions, String decisionCode) {}
}
