package com.sstlfsj.rule.kernel.api.model;

import com.sstlfsj.rule.kernel.api.model.ast.AndNode;
import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RuleVersionSnapshotBuilderTest {

    @Test
    void builder_minimalFields_defaultsApplied() {
        RuleVersionSnapshot snap = RuleVersionSnapshot.builder()
                .ruleVersionId(1L)
                .tenantId("t1")
                .sceneCode("fraud")
                .conditionAst(new AndNode(List.of(), null, null))
                .build();

        assertThat(snap.kind()).isEqualTo("AST_BOOLEAN");
        assertThat(snap.preGates()).isEmpty();
        assertThat(snap.decisionBindings()).isEmpty();
        assertThat(snap.triggerEventTypes()).isEmpty();
    }

    @Test
    void builder_fullFields_roundtrip() {
        RuleVersionSnapshot snap = RuleVersionSnapshot.builder()
                .ruleVersionId(2L)
                .tenantId("t1")
                .sceneCode("payment")
                .kind("SCORECARD")
                .conditionAst(new AndNode(List.of(), null, null))
                .addTriggerEventType("TRANSACTION")
                .addDecisionBinding("BLOCK", 100)
                .build();

        assertThat(snap.ruleVersionId()).isEqualTo(2L);
        assertThat(snap.kind()).isEqualTo("SCORECARD");
        assertThat(snap.triggerEventTypes()).containsExactly("TRANSACTION");
        assertThat(snap.decisionBindings()).hasSize(1);
        assertThat(snap.decisionBindings().get(0).decisionCode()).isEqualTo("BLOCK");
        assertThat(snap.decisionBindings().get(0).priority()).isEqualTo(100);
    }

    @Test
    void builder_withConditionNode_realRule() {
        ConditionNode condition = new ConditionNode("GT", "amount", null,
                Map.of("threshold", 1000), 0.0);
        RuleVersionSnapshot snap = RuleVersionSnapshot.builder()
                .ruleVersionId(3L)
                .tenantId("t1")
                .sceneCode("fraud")
                .conditionAst(new AndNode(List.of(condition), null, null))
                .addTriggerEventType("TRANSACTION")
                .addDecisionBinding("BLOCK", 100)
                .build();

        assertThat(((AstBody) snap.body()).conditionAst()).isInstanceOf(AndNode.class);
        AndNode root = (AndNode) ((AstBody) snap.body()).conditionAst();
        assertThat(root.children()).hasSize(1);
        assertThat(((ConditionNode) root.children().get(0)).conditionType()).isEqualTo("GT");
    }

    @Test
    void builderCarriesCodeAndVersion() {
        RuleVersionSnapshot s = RuleVersionSnapshot.builder()
                .ruleVersionId(100L).sceneCode("scene").tenantId("t1")
                .code("large-trade").version(3L)
                .build();
        assertThat(s.code()).isEqualTo("large-trade");
        assertThat(s.version()).isEqualTo(3L);
    }
}
