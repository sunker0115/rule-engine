package com.sstlfsj.rule.sdk.source;

import com.sstlfsj.rule.kernel.api.model.*;
import com.sstlfsj.rule.sdk.Condition;
import com.sstlfsj.rule.sdk.FactResolver;
import com.sstlfsj.rule.sdk.annotation.Decide;
import com.sstlfsj.rule.sdk.annotation.Fact;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AnnotatedDecideExecutorTest {

    static class Rule {
        @Decide
        public String decide(@Fact("amount") Integer amount) {
            return amount > 5000 ? "REJECT" : "PASS";
        }
    }

    private RuleVersionSnapshot snap(String key) {
        return RuleVersionSnapshot.builder()
                .ruleVersionId(1L).tenantId("t").sceneCode("s").code("r").version(1L)
                .kind("__anno_decide")
                .conditionAst(Condition.of(key, Map.of()).toAst())
                .addTriggerEventType("evt")
                .addDecisionBinding("PASS", 10)
                .addDecisionBinding("REJECT", 90)
                .build();
    }

    private EvalContext ctx(int amount) {
        RuleEvent e = RuleEvent.builder().tenantId("t").sceneCode("s").eventType("evt")
                .subjectId("u").eventId("e1").occurredAt(Instant.now())
                .payload(Map.of("amount", amount)).source(EventSource.SDK).build();
        return new EvalContext("t", e, null, Map.of(), Instant.now());
    }

    @Test
    void decide_returnsBoundDecision() throws Exception {
        Method m = Rule.class.getMethod("decide", Integer.class);
        var inv = new AnnotatedDecideExecutor.Invocation(new Rule(), m, new FactResolver());
        var exec = new AnnotatedDecideExecutor(Map.of("k1", inv));

        EvalResult hi = exec.execute(snap("k1"), ctx(8000));
        assertThat(hi.ruleHit()).isTrue();
        assertThat(hi.finalDecision().code()).isEqualTo("REJECT");
        assertThat(hi.finalDecision().fromRuleCode()).isEqualTo("r");

        EvalResult lo = exec.execute(snap("k1"), ctx(100));
        assertThat(lo.finalDecision().code()).isEqualTo("PASS");
    }
}
