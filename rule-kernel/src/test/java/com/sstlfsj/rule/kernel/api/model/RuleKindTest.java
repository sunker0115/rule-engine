package com.sstlfsj.rule.kernel.api.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** RuleKind 标签契约：tag 值 == DB rule_definition.kind / rule_version.kind ENUM，不可随意改动。 */
class RuleKindTest {

    @Test
    void tag_matchesPersistedContract() {
        assertEquals("AST_BOOLEAN", RuleKind.AST_BOOLEAN.tag());
        assertEquals("SCORECARD", RuleKind.SCORECARD.tag());
        assertEquals("DECISION_TREE", RuleKind.DECISION_TREE.tag());
        assertEquals("DECISION_TABLE", RuleKind.DECISION_TABLE.tag());
        assertEquals("EXPRESSION_SCRIPT", RuleKind.EXPRESSION_SCRIPT.tag());
        assertEquals("DECISION_FLOW", RuleKind.DECISION_FLOW.tag());
    }
}
