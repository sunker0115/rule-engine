package com.sstlfsj.rule.sdk.source;

import com.sstlfsj.rule.kernel.api.model.*;
import com.sstlfsj.rule.sdk.Condition;
import com.sstlfsj.rule.sdk.FactResolver;
import com.sstlfsj.rule.sdk.annotation.Fact;
import com.sstlfsj.rule.sdk.annotation.Score;
import com.sstlfsj.rule.sdk.annotation.ScoreBand;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AnnotatedScoreExecutorTest {

    static class Rule {
        @Score
        @ScoreBand(min = 0, decision = "PASS")
        @ScoreBand(min = 60, decision = "REVIEW")
        @ScoreBand(min = 90, decision = "REJECT")
        public double score(@Fact("risk") Integer risk) { return risk; }
    }

    private EvalResult run(int risk) throws Exception {
        Method m = Rule.class.getMethod("score", Integer.class);
        var inv = new AnnotatedScoreExecutor.Invocation(new Rule(), m, new FactResolver(),
                List.of(new AnnotatedScoreExecutor.Band(0, "PASS"),
                        new AnnotatedScoreExecutor.Band(60, "REVIEW"),
                        new AnnotatedScoreExecutor.Band(90, "REJECT")));
        var exec = new AnnotatedScoreExecutor(Map.of("k", inv));
        RuleVersionSnapshot snap = RuleVersionSnapshot.builder()
                .ruleVersionId(1L).tenantId("t").sceneCode("s").code("r").version(1L)
                .kind("__anno_score").conditionAst(Condition.of("k", Map.of()).toAst())
                .addTriggerEventType("evt")
                .addDecisionBinding("PASS", 10).addDecisionBinding("REVIEW", 50)
                .addDecisionBinding("REJECT", 90).build();
        RuleEvent e = RuleEvent.builder().tenantId("t").sceneCode("s").eventType("evt")
                .subjectId("u").eventId("e1").occurredAt(Instant.now())
                .payload(Map.of("risk", risk)).source(EventSource.SDK).build();
        return exec.execute(snap, new EvalContext("t", e, null, Map.of(), Instant.now()));
    }

    @Test
    void score_mapsToHighestMatchingBand_andSetsScore() throws Exception {
        EvalResult r = run(75);
        assertThat(r.finalDecision().code()).isEqualTo("REVIEW");  // 75 ≥ 60,< 90
        assertThat(r.score()).isEqualTo(75.0);

        assertThat(run(95).finalDecision().code()).isEqualTo("REJECT");
        assertThat(run(10).finalDecision().code()).isEqualTo("PASS");
    }
}
