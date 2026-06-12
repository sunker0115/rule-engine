package com.sstlfsj.rule.kernel.api.model;

/**
 * 评估错误码的单一来源（落库 error_code 列 + API 响应契约）。
 * 各执行器/取数链路统一引用此枚举，避免散落的字符串字面量漂移。
 * 模型层的 errorCode 字段仍以 String 承载（容纳 provider 开放码），
 * 调用点用本枚举值经各模型的 enum 工厂重载落为 {@link #name()}，字符串值与历史一致。
 */
public enum EvalErrorCode {
    /** 指标取数失败。 */
    METRIC_FETCH_FAIL,
    /** 指标 provider（注解 metric source 等）求值抛错的规范码。 */
    METRIC_SOURCE_EVAL_ERROR,
    /** 未注册对应 conditionType 的算子。 */
    NO_EVALUATOR,
    /** 条件求值抛异常的兜底错误码。 */
    CONDITION_EVAL_ERROR,
    /** 评分卡 AST 类型不匹配。 */
    SCORECARD_AST_TYPE_MISMATCH,
    /** 决策树 AST 类型不匹配。 */
    DECISION_TREE_AST_TYPE_MISMATCH,
    /** 决策树遍历到非预期节点类型。 */
    DECISION_TREE_UNEXPECTED_NODE,
    /** 决策表 AST 类型不匹配。 */
    DECISION_TABLE_AST_TYPE_MISMATCH,
    /** @Decide 合成执行器:快照坐标未注册到调用表。 */
    ANNO_DECIDE_UNREGISTERED,
    /** @Decide 合成执行器:返回的码全部非法、无有效命中。 */
    ANNO_DECIDE_NO_HIT,
    /** @Score 合成执行器:快照坐标未注册到调用表。 */
    ANNO_SCORE_UNREGISTERED,
    /** @Decide 方法体抛异常。 */
    DECIDE_EVAL_ERROR,
    /** @Score 方法体抛异常。 */
    SCORE_EVAL_ERROR,
    /** 返回/命中的决策码不在 decisionBindings 中。 */
    INVALID_DECISION_CODE,
    /** EXPRESSION_SCRIPT:kind 为脚本但 snapshot.script() 为 null。 */
    SCRIPT_SOURCE_MISSING,
    /** EXPRESSION_SCRIPT:script.lang 无对应已注册 ExpressionEngine。 */
    SCRIPT_NO_ENGINE,
    /** EXPRESSION_SCRIPT:运行期求值抛错。 */
    SCRIPT_EVAL_ERROR
}
