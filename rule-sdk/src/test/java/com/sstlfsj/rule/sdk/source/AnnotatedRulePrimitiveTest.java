package com.sstlfsj.rule.sdk.source;

import com.sstlfsj.rule.kernel.api.annotation.DecisionBinding;
import com.sstlfsj.rule.kernel.api.annotation.RuleDef;
import com.sstlfsj.rule.sdk.FactResolver;
import com.sstlfsj.rule.sdk.annotation.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

class AnnotatedRulePrimitiveTest {

    @RuleDef(code = "d", sceneCode = "s", decisions = {
            @DecisionBinding(code = "PASS"), @DecisionBinding(code = "REJECT", priority = 90)})
    static class DecideRule {
        @Decide public String decide(@Fact("amount") Integer a) { return a > 100 ? "REJECT" : "PASS"; }
    }

    @RuleDef(code = "bad", sceneCode = "s")
    static class TwoPrimitives {
        @Condition public boolean c() { return true; }
        @Decide public String d() { return "X"; }
    }

    @Test
    void scan_buildsDecideRegistryAndKind() {
        AnnotatedRuleScanner.ScanResult r =
                new AnnotatedRuleScanner(new FactResolver(), "t").scan(List.of(new DecideRule()));
        assertThat(r.decideInvocations()).hasSize(1);
        assertThat(r.snapshots()).hasSize(1);
        assertThat(r.snapshots().get(0).kind()).isEqualTo("__anno_decide");
    }

    @Test
    void scan_rejectsMultiplePrimitives() {
        assertThatThrownBy(() ->
                new AnnotatedRuleScanner(new FactResolver(), "t").scan(List.of(new TwoPrimitives())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("判定原语");
    }
}
