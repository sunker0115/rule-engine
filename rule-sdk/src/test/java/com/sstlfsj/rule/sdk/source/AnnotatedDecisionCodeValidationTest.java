package com.sstlfsj.rule.sdk.source;

import com.sstlfsj.rule.kernel.api.annotation.DecisionBinding;
import com.sstlfsj.rule.kernel.api.annotation.RuleDef;
import com.sstlfsj.rule.sdk.FactResolver;
import com.sstlfsj.rule.sdk.annotation.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

class AnnotatedDecisionCodeValidationTest {

    @RuleDef(code = "s", sceneCode = "x", decisions = @DecisionBinding(code = "PASS"))
    static class ScoreBandUnbound {
        @Score @ScoreBand(min = 0, decision = "GHOST")  // GHOST 未在 decisions 声明
        public double sc() { return 1; }
    }

    @Test
    void scan_rejectsScoreBandReferencingUndeclaredDecision() {
        assertThatThrownBy(() ->
                new AnnotatedRuleScanner(new FactResolver(), "t").scan(List.of(new ScoreBandUnbound())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("GHOST");
    }
}
