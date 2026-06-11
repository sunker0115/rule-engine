package com.sstlfsj.rule.kernel.api.model;

/**
 * 评估错误码的单一来源（落库 error_code 列 + API 响应契约）。
 * 各执行器/取数链路统一引用这里的常量，避免散落的字符串字面量漂移。
 */
public final class EvalErrorCode {

    /** 指标取数失败。 */
    public static final String METRIC_FETCH_FAIL = "METRIC_FETCH_FAIL";
    /** 未注册对应 conditionType 的算子。 */
    public static final String NO_EVALUATOR = "NO_EVALUATOR";
    /** 条件求值抛异常的兜底错误码。 */
    public static final String CONDITION_EVAL_ERROR = "CONDITION_EVAL_ERROR";
    /** 评分卡 AST 类型不匹配。 */
    public static final String SCORECARD_AST_TYPE_MISMATCH = "SCORECARD_AST_TYPE_MISMATCH";
    /** 决策树 AST 类型不匹配。 */
    public static final String DECISION_TREE_AST_TYPE_MISMATCH = "DECISION_TREE_AST_TYPE_MISMATCH";
    /** 决策树遍历到非预期节点类型。 */
    public static final String DECISION_TREE_UNEXPECTED_NODE = "DECISION_TREE_UNEXPECTED_NODE";
    /** 决策表 AST 类型不匹配。 */
    public static final String DECISION_TABLE_AST_TYPE_MISMATCH = "DECISION_TABLE_AST_TYPE_MISMATCH";

    private EvalErrorCode() {}
}
