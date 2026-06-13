package com.sstlfsj.rule.sdk;

import com.sstlfsj.rule.kernel.api.model.ConditionParams;
import com.sstlfsj.rule.kernel.api.model.ConditionTypes;
import com.sstlfsj.rule.kernel.api.model.ValueRef;
import com.sstlfsj.rule.kernel.api.model.ast.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * DSL 工厂类，链式构造规则条件，隐藏 AST 节点构造细节。
 * toAst() 生成标准 AstNode，与 EvalEngine 评估链路完全兼容。
 */
public final class Condition {

    private final AstNode ast;

    private Condition(AstNode ast) {
        this.ast = ast;
    }

    /** 将条件转换为 AstNode，供 RuleVersionSnapshot.builder().conditionAst() 使用。 */
    public AstNode toAst() { return ast; }

    // ── 叶子条件工厂方法 ──────────────────────────────────────────────────────

    public static Condition gt(String metric, Object threshold) {
        return leaf(ConditionTypes.GT, metric, Map.of(ConditionParams.THRESHOLD, threshold));
    }

    public static Condition gte(String metric, Object threshold) {
        return leaf(ConditionTypes.GTE, metric, Map.of(ConditionParams.THRESHOLD, threshold));
    }

    public static Condition lt(String metric, Object threshold) {
        return leaf(ConditionTypes.LT, metric, Map.of(ConditionParams.THRESHOLD, threshold));
    }

    public static Condition lte(String metric, Object threshold) {
        return leaf(ConditionTypes.LTE, metric, Map.of(ConditionParams.THRESHOLD, threshold));
    }

    public static Condition eq(String metric, Object value) {
        // 参数键须对齐 evaluator：EqEvaluator/NeqEvaluator 读 params.get("threshold")
        return leaf(ConditionTypes.EQ, metric, Map.of(ConditionParams.THRESHOLD, value));
    }

    public static Condition neq(String metric, Object value) {
        return leaf(ConditionTypes.NEQ, metric, Map.of(ConditionParams.THRESHOLD, value));
    }

    public static Condition in(String metric, Object... values) {
        return leaf(ConditionTypes.IN, metric, Map.of(ConditionParams.VALUES, Arrays.asList(values)));
    }

    public static Condition notIn(String metric, Object... values) {
        return leaf(ConditionTypes.NOT_IN, metric, Map.of(ConditionParams.VALUES, Arrays.asList(values)));
    }

    public static Condition between(String metric, Object min, Object max) {
        return leaf(ConditionTypes.BETWEEN, metric, Map.of(ConditionParams.MIN, min, ConditionParams.MAX, max));
    }

    public static Condition contains(String metric, String value) {
        // 参数键须对齐 evaluator：ContainsEvaluator 读 params.get("element")
        return leaf(ConditionTypes.CONTAINS, metric, Map.of(ConditionParams.ELEMENT, value));
    }

    public static Condition matches(String metric, String pattern) {
        // 参数键须为 "regex"：MatchesEvaluator 读 params.get("regex")，旧 "pattern" 键会导致永不命中
        return leaf(ConditionTypes.MATCHES, metric, Map.of(ConditionParams.REGEX, pattern));
    }

    public static Condition startsWith(String metric, String value) {
        // 参数键须对齐 evaluator：StartsWithEvaluator 读 params.get("prefix")
        return leaf(ConditionTypes.STARTS_WITH, metric, Map.of(ConditionParams.PREFIX, value));
    }

    public static Condition endsWith(String metric, String value) {
        // 参数键须对齐 evaluator：EndsWithEvaluator 读 params.get("suffix")
        return leaf(ConditionTypes.ENDS_WITH, metric, Map.of(ConditionParams.SUFFIX, value));
    }

    /** 自定义算子（需配合 RuleEngineClient.Builder.addEvaluator() 注册）。 */
    public static Condition of(String conditionType, String metric, Map<String, Object> params) {
        return leaf(conditionType, metric, params);
    }

    /** 自定义算子，无绑定 metric（直接读 payload/context 的算子）。 */
    public static Condition of(String conditionType, Map<String, Object> params) {
        return of(conditionType, null, params);
    }

    // ── payload 直接引用工厂（valueRef=PAYLOAD，字段名复用 metricCode，直接读 event.payload）──

    /** payload 字段 field 大于 threshold。 */
    public static Condition payloadGt(String field, Object threshold) {
        return leaf(ConditionTypes.GT, field, Map.of(ConditionParams.THRESHOLD, threshold), ValueRef.PAYLOAD);
    }

    /** payload 字段 field 大于等于 threshold。 */
    public static Condition payloadGte(String field, Object threshold) {
        return leaf(ConditionTypes.GTE, field, Map.of(ConditionParams.THRESHOLD, threshold), ValueRef.PAYLOAD);
    }

    /** payload 字段 field 小于 threshold。 */
    public static Condition payloadLt(String field, Object threshold) {
        return leaf(ConditionTypes.LT, field, Map.of(ConditionParams.THRESHOLD, threshold), ValueRef.PAYLOAD);
    }

    /** payload 字段 field 小于等于 threshold。 */
    public static Condition payloadLte(String field, Object threshold) {
        return leaf(ConditionTypes.LTE, field, Map.of(ConditionParams.THRESHOLD, threshold), ValueRef.PAYLOAD);
    }

    /** payload 字段 field 等于 value。 */
    public static Condition payloadEq(String field, Object value) {
        return leaf(ConditionTypes.EQ, field, Map.of(ConditionParams.THRESHOLD, value), ValueRef.PAYLOAD);
    }

    /** payload 字段 field 不等于 value。 */
    public static Condition payloadNeq(String field, Object value) {
        return leaf(ConditionTypes.NEQ, field, Map.of(ConditionParams.THRESHOLD, value), ValueRef.PAYLOAD);
    }

    /** payload 字段 field 属于 values 集合。 */
    public static Condition payloadIn(String field, Object... values) {
        return leaf(ConditionTypes.IN, field, Map.of(ConditionParams.VALUES, Arrays.asList(values)), ValueRef.PAYLOAD);
    }

    /** payload 字段 field 落在 [min, max] 闭区间。 */
    public static Condition payloadBetween(String field, Object min, Object max) {
        return leaf(ConditionTypes.BETWEEN, field, Map.of(ConditionParams.MIN, min, ConditionParams.MAX, max), ValueRef.PAYLOAD);
    }

    /** 恒真条件（空 AND 节点）。 */
    public static Condition always() {
        return new Condition(new AndNode(List.of(), null, null));
    }

    /** 恒假条件（空 OR 节点，无子节点时求值 false）。 */
    public static Condition never() {
        return new Condition(new OrNode(List.of(), null, null));
    }

    // ── 逻辑组合 ─────────────────────────────────────────────────────────────

    /** 与当前条件 AND 组合，同级多个 and() 调用展平到同一 AndNode。 */
    public Condition and(Condition other) {
        List<AstNode> children = new ArrayList<>();
        if (ast instanceof AndNode and) {
            children.addAll(and.children());
        } else {
            children.add(ast);
        }
        if (other.ast instanceof AndNode and) {
            children.addAll(and.children());
        } else {
            children.add(other.ast);
        }
        return new Condition(new AndNode(children, null, null));
    }

    /** 与当前条件 OR 组合，同级多个 or() 调用展平到同一 OrNode。 */
    public Condition or(Condition other) {
        List<AstNode> children = new ArrayList<>();
        if (ast instanceof OrNode or) {
            children.addAll(or.children());
        } else {
            children.add(ast);
        }
        if (other.ast instanceof OrNode or) {
            children.addAll(or.children());
        } else {
            children.add(other.ast);
        }
        return new Condition(new OrNode(children, null, null));
    }

    /** 对当前条件取反，生成 NOT 节点。 */
    public Condition not() {
        return new Condition(new NotNode(ast));
    }

    // ── 内部工具 ─────────────────────────────────────────────────────────────

    private static Condition leaf(String conditionType, String metric, Map<String, Object> params) {
        return leaf(conditionType, metric, params, ValueRef.METRIC);
    }

    private static Condition leaf(String conditionType, String metric, Map<String, Object> params,
                                  ValueRef valueRef) {
        return new Condition(new ConditionNode(conditionType, metric, null, params, 0.0, null, valueRef));
    }
}
