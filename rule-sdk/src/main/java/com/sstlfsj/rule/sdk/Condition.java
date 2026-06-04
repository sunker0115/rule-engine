package com.sstlfsj.rule.sdk;

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
        return leaf("GT", metric, Map.of("threshold", threshold));
    }

    public static Condition gte(String metric, Object threshold) {
        return leaf("GTE", metric, Map.of("threshold", threshold));
    }

    public static Condition lt(String metric, Object threshold) {
        return leaf("LT", metric, Map.of("threshold", threshold));
    }

    public static Condition lte(String metric, Object threshold) {
        return leaf("LTE", metric, Map.of("threshold", threshold));
    }

    public static Condition eq(String metric, Object value) {
        return leaf("EQ", metric, Map.of("value", value));
    }

    public static Condition neq(String metric, Object value) {
        return leaf("NEQ", metric, Map.of("value", value));
    }

    public static Condition in(String metric, Object... values) {
        return leaf("IN", metric, Map.of("values", Arrays.asList(values)));
    }

    public static Condition notIn(String metric, Object... values) {
        return leaf("NOT_IN", metric, Map.of("values", Arrays.asList(values)));
    }

    public static Condition between(String metric, Object min, Object max) {
        return leaf("BETWEEN", metric, Map.of("min", min, "max", max));
    }

    public static Condition contains(String metric, String value) {
        return leaf("CONTAINS", metric, Map.of("value", value));
    }

    public static Condition matches(String metric, String pattern) {
        return leaf("MATCHES", metric, Map.of("pattern", pattern));
    }

    public static Condition startsWith(String metric, String value) {
        return leaf("STARTS_WITH", metric, Map.of("value", value));
    }

    public static Condition endsWith(String metric, String value) {
        return leaf("ENDS_WITH", metric, Map.of("value", value));
    }

    /** 自定义算子（需配合 RuleEngineClient.Builder.addEvaluator() 注册）。 */
    public static Condition of(String conditionType, String metric, Map<String, Object> params) {
        return leaf(conditionType, metric, params);
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
        return new Condition(new ConditionNode(conditionType, metric, null, params, 0.0));
    }
}
