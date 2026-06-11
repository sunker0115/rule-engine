package com.sstlfsj.rule.kernel.api.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** EvalErrorCode 值契约：error_code 是持久化/API 约定，不可随意改动。 */
class EvalErrorCodeTest {

    @Test
    void codes_matchPersistedContract() {
        assertEquals("METRIC_FETCH_FAIL", EvalErrorCode.METRIC_FETCH_FAIL);
        assertEquals("NO_EVALUATOR", EvalErrorCode.NO_EVALUATOR);
        assertEquals("CONDITION_EVAL_ERROR", EvalErrorCode.CONDITION_EVAL_ERROR);
        assertEquals("SCORECARD_AST_TYPE_MISMATCH", EvalErrorCode.SCORECARD_AST_TYPE_MISMATCH);
        assertEquals("DECISION_TREE_AST_TYPE_MISMATCH", EvalErrorCode.DECISION_TREE_AST_TYPE_MISMATCH);
        assertEquals("DECISION_TREE_UNEXPECTED_NODE", EvalErrorCode.DECISION_TREE_UNEXPECTED_NODE);
        assertEquals("DECISION_TABLE_AST_TYPE_MISMATCH", EvalErrorCode.DECISION_TABLE_AST_TYPE_MISMATCH);
    }
}
