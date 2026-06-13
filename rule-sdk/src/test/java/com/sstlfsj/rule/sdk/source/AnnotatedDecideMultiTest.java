package com.sstlfsj.rule.sdk.source;

import com.sstlfsj.rule.kernel.api.model.*;
import com.sstlfsj.rule.sdk.Condition;
import com.sstlfsj.rule.sdk.FactResolver;
import com.sstlfsj.rule.sdk.annotation.Decide;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AnnotatedDecideMultiTest {

    static class Rule {
        @Decide
        public List<String> decide() { return List.of("REVIEW", "NOTIFY"); }
    }

    @Test
    void decide_returnsMultipleDecisions() throws Exception {
        Method m = Rule.class.getMethod("decide");
        var inv = new AnnotatedDecideExecutor.Invocation(new Rule(), m, new FactResolver());
        var exec = new AnnotatedDecideExecutor(Map.of("k", inv));
        RuleVersionSnapshot snap = RuleVersionSnapshot.builder()
                .ruleVersionId(1L).tenantId("t").sceneCode("s").code("r").version(1L)
                .kind("__anno_decide").conditionAst(Condition.of("k", Map.of()).toAst())
                .addTriggerEventType("evt")
                .addDecisionBinding("REVIEW", 50).addDecisionBinding("NOTIFY", 10).build();
        RuleEvent e = RuleEvent.builder().tenantId("t").sceneCode("s").eventType("evt")
                .subjectId("u").eventId("e1").occurredAt(Instant.now())
                .payload(Map.of()).source(EventSource.SDK).build();

        EvalResult r = exec.execute(snap, new EvalContext("t", e, null, Map.of(), Instant.now()));
        assertThat(r.hitDecisions()).extracting(Decision::code).containsExactly("REVIEW", "NOTIFY");
        assertThat(r.finalDecision().code()).isEqualTo("REVIEW");  // 最高优先级
    }
}
