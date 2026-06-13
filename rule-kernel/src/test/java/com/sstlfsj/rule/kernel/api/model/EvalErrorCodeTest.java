package com.sstlfsj.rule.kernel.api.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** EvalErrorCode 值契约：error_code 是持久化/API 约定，枚举 name() 不可随意改动。 */
class EvalErrorCodeTest {

    @Test
    void codes_matchPersistedContract() {
        // 旧 kernel 常量(name 必须逐字不变,保证落库/序列化字符串零漂移)
        assertEquals("METRIC_FETCH_FAIL", EvalErrorCode.METRIC_FETCH_FAIL.name());
        assertEquals("NO_EVALUATOR", EvalErrorCode.NO_EVALUATOR.name());
        assertEquals("CONDITION_EVAL_ERROR", EvalErrorCode.CONDITION_EVAL_ERROR.name());
        assertEquals("SCORECARD_AST_TYPE_MISMATCH", EvalErrorCode.SCORECARD_AST_TYPE_MISMATCH.name());
        assertEquals("DECISION_TREE_AST_TYPE_MISMATCH", EvalErrorCode.DECISION_TREE_AST_TYPE_MISMATCH.name());
        assertEquals("DECISION_TREE_UNEXPECTED_NODE", EvalErrorCode.DECISION_TREE_UNEXPECTED_NODE.name());
        assertEquals("DECISION_TABLE_AST_TYPE_MISMATCH", EvalErrorCode.DECISION_TABLE_AST_TYPE_MISMATCH.name());
        // eval 域 provider 规范码(纳入单一真相源)
        assertEquals("METRIC_SOURCE_EVAL_ERROR", EvalErrorCode.METRIC_SOURCE_EVAL_ERROR.name());
        // 从 SDK 字面量收编而来
        assertEquals("ANNO_DECIDE_UNREGISTERED", EvalErrorCode.ANNO_DECIDE_UNREGISTERED.name());
        assertEquals("ANNO_DECIDE_NO_HIT", EvalErrorCode.ANNO_DECIDE_NO_HIT.name());
        assertEquals("ANNO_SCORE_UNREGISTERED", EvalErrorCode.ANNO_SCORE_UNREGISTERED.name());
        assertEquals("DECIDE_EVAL_ERROR", EvalErrorCode.DECIDE_EVAL_ERROR.name());
        assertEquals("SCORE_EVAL_ERROR", EvalErrorCode.SCORE_EVAL_ERROR.name());
        assertEquals("INVALID_DECISION_CODE", EvalErrorCode.INVALID_DECISION_CODE.name());
        // 预置脚本功能码(本单元仅声明,后续单元使用)
        assertEquals("SCRIPT_SOURCE_MISSING", EvalErrorCode.SCRIPT_SOURCE_MISSING.name());
        assertEquals("SCRIPT_NO_ENGINE", EvalErrorCode.SCRIPT_NO_ENGINE.name());
        assertEquals("SCRIPT_EVAL_ERROR", EvalErrorCode.SCRIPT_EVAL_ERROR.name());
    }
}
