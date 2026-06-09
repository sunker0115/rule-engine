package com.sstlfsj.rule.kernel.api.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** NodeType 标签契约：tag 值是持久化/前端约定，不可随意改动。 */
class NodeTypeTest {

    @Test
    void tag_matchesPersistedContract() {
        assertEquals("AndNode", NodeType.AND.tag());
        assertEquals("OrNode", NodeType.OR.tag());
        assertEquals("NotNode", NodeType.NOT.tag());
        assertEquals("XorNode", NodeType.XOR.tag());
        assertEquals("IfNode", NodeType.IF.tag());
        assertEquals("ConditionNode", NodeType.CONDITION.tag());
        assertEquals("DecisionLeafNode", NodeType.DECISION_LEAF.tag());
        assertEquals("DecisionTableRow", NodeType.DECISION_TABLE_ROW.tag());
        assertEquals("ScorecardRoot", NodeType.SCORECARD_ROOT.tag());
    }
}
