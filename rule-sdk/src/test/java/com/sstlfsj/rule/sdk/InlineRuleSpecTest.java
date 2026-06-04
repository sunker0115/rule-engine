package com.sstlfsj.rule.sdk;

import com.sstlfsj.rule.kernel.api.annotation.DecisionBinding;
import com.sstlfsj.rule.kernel.api.annotation.RuleDef;
import com.sstlfsj.rule.kernel.api.model.ast.AndNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InlineRuleSpecTest {

    @RuleDef(id = 1L, tenantId = "t1", sceneCode = "fraud",
             trigger = "TRANSACTION",
             decisions = @DecisionBinding(code = "BLOCK", priority = 100))
    static class AmountRule implements InlineRuleSpec {
        @Override
        public Condition condition() {
            return Condition.gt("amount", 1000);
        }
    }

    @Test
    void condition_returnsExpectedAst() {
        InlineRuleSpec spec = new AmountRule();
        assertThat(spec.condition().toAst()).isNotNull();
    }

    @Test
    void ruleDefAnnotation_isReadable() {
        RuleDef ann = AmountRule.class.getAnnotation(RuleDef.class);
        assertThat(ann).isNotNull();
        assertThat(ann.id()).isEqualTo(1L);
        assertThat(ann.sceneCode()).isEqualTo("fraud");
    }

    @Test
    void always_condition_producesEmptyAndNode() {
        InlineRuleSpec alwaysTrue = new InlineRuleSpec() {
            @Override public Condition condition() { return Condition.always(); }
        };
        assertThat(alwaysTrue.condition().toAst()).isInstanceOf(AndNode.class);
    }
}
