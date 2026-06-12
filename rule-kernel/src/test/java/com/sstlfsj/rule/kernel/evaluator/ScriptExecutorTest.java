package com.sstlfsj.rule.kernel.evaluator;

import com.sstlfsj.rule.kernel.api.model.*;
import com.sstlfsj.rule.kernel.api.spi.expression.CompiledExpression;
import com.sstlfsj.rule.kernel.api.spi.expression.ExpressionEngine;
import com.sstlfsj.rule.kernel.internal.evaluator.ScriptExecutor;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ScriptExecutorTest {

    // fake engine:compile 返回固定产物,evaluate 返回预设值(或抛错)
    private static final class FakeEngine implements ExpressionEngine {
        private final Object result;
        private final boolean throwOnEval;
        FakeEngine(Object result) { this(result, false); }
        FakeEngine(Object result, boolean throwOnEval) { this.result = result; this.throwOnEval = throwOnEval; }
        public String lang() { return "CEL"; }
        public CompiledExpression compile(String source) { return Set::of; }
        public Object evaluate(CompiledExpression c, Map<String, Object> b) {
            if (throwOnEval) throw new RuntimeException("boom");
            return result;
        }
    }

    private EvalContext ctx() {
        RuleEvent event = new RuleEvent("t1", "scene", "TXN", "u1", "e1", Instant.now(),
                Map.of(), Map.of(), EventSource.HTTP);
        return new EvalContext("t1", event, null, Map.of(), Instant.parse("2026-06-01T00:00:00Z"));
    }

    private ScriptExecutor executor(ExpressionEngine engine) {
        return new ScriptExecutor(Map.of(engine.lang(), engine));
    }

    @Test
    void booleanTrueHitsWithoutDecision() {
        RuleVersionSnapshot snap = new RuleVersionSnapshot(1L, "scene", "t1", null,
                List.of(), List.of(new RuleVersionSnapshot.DecisionBinding("PASS", 10)),
                List.of(), RuleKind.EXPRESSION_SCRIPT.tag(), "R1", 1L, List.of(), List.of(),
                new ScriptSource("expr", "CEL"));
        EvalResult r = executor(new FakeEngine(Boolean.TRUE)).execute(snap, ctx());
        assertThat(r.ruleHit()).isTrue();
        assertThat(r.hitDecisions()).isEmpty();      // 引擎裁决 binding(对标 AST_BOOLEAN)
        assertThat(r.finalDecision()).isNull();
    }

    @Test
    void booleanFalseMisses() {
        RuleVersionSnapshot snap = new RuleVersionSnapshot(1L, "scene", "t1", null,
                List.of(), List.of(), List.of(), RuleKind.EXPRESSION_SCRIPT.tag(), "R1", 1L,
                List.of(), List.of(), new ScriptSource("expr", "CEL"));
        EvalResult r = executor(new FakeEngine(Boolean.FALSE)).execute(snap, ctx());
        assertThat(r.ruleHit()).isFalse();
    }

    @Test
    void stringReturnsBoundDecision() {
        RuleVersionSnapshot snap = new RuleVersionSnapshot(1L, "scene", "t1", null,
                List.of(), List.of(new RuleVersionSnapshot.DecisionBinding("REVIEW", "审核", 10)),
                List.of(), RuleKind.EXPRESSION_SCRIPT.tag(), "R1", 1L, List.of(), List.of(),
                new ScriptSource("expr", "CEL"));
        EvalResult r = executor(new FakeEngine("REVIEW")).execute(snap, ctx());
        assertThat(r.ruleHit()).isTrue();
        assertThat(r.finalDecision().code()).isEqualTo("REVIEW");
        assertThat(r.hitDecisions()).hasSize(1);
    }

    @Test
    void stringNotInBindingsYieldsInvalidDecisionCode() {
        RuleVersionSnapshot snap = new RuleVersionSnapshot(1L, "scene", "t1", null,
                List.of(), List.of(new RuleVersionSnapshot.DecisionBinding("PASS", 10)),
                List.of(), RuleKind.EXPRESSION_SCRIPT.tag(), "R1", 1L, List.of(), List.of(),
                new ScriptSource("expr", "CEL"));
        EvalResult r = executor(new FakeEngine("NOT_BOUND")).execute(snap, ctx());
        assertThat(r.ruleHit()).isFalse();
        assertThat(r.errorCode()).isEqualTo(EvalErrorCode.INVALID_DECISION_CODE.name());
    }

    @Test
    void numberSetsScoreOnly() {
        RuleVersionSnapshot snap = new RuleVersionSnapshot(1L, "scene", "t1", null,
                List.of(), List.of(), List.of(), RuleKind.EXPRESSION_SCRIPT.tag(), "R1", 1L,
                List.of(), List.of(), new ScriptSource("expr", "CEL"));
        EvalResult r = executor(new FakeEngine(72.5)).execute(snap, ctx());
        assertThat(r.score()).isEqualTo(72.5);
        assertThat(r.ruleHit()).isFalse();           // score-only;决策分档留 Plan 4
        assertThat(r.finalDecision()).isNull();
    }

    @Test
    void nullResultMisses() {
        RuleVersionSnapshot snap = new RuleVersionSnapshot(1L, "scene", "t1", null,
                List.of(), List.of(), List.of(), RuleKind.EXPRESSION_SCRIPT.tag(), "R1", 1L,
                List.of(), List.of(), new ScriptSource("expr", "CEL"));
        EvalResult r = executor(new FakeEngine(null)).execute(snap, ctx());
        assertThat(r.ruleHit()).isFalse();
    }

    @Test
    void nullScriptYieldsSourceMissing() {
        RuleVersionSnapshot snap = new RuleVersionSnapshot(1L, "scene", "t1", null,
                List.of(), List.of(), List.of(), RuleKind.EXPRESSION_SCRIPT.tag(), "R1", 1L,
                List.of(), List.of(), null);
        EvalResult r = executor(new FakeEngine(Boolean.TRUE)).execute(snap, ctx());
        assertThat(r.errorCode()).isEqualTo(EvalErrorCode.SCRIPT_SOURCE_MISSING.name());
    }

    @Test
    void unknownLangYieldsNoEngine() {
        RuleVersionSnapshot snap = new RuleVersionSnapshot(1L, "scene", "t1", null,
                List.of(), List.of(), List.of(), RuleKind.EXPRESSION_SCRIPT.tag(), "R1", 1L,
                List.of(), List.of(), new ScriptSource("expr", "LUA"));
        EvalResult r = executor(new FakeEngine(Boolean.TRUE)).execute(snap, ctx()); // engine.lang()=CEL,无 LUA
        assertThat(r.errorCode()).isEqualTo(EvalErrorCode.SCRIPT_NO_ENGINE.name());
    }

    @Test
    void evalThrowYieldsScriptEvalError() {
        RuleVersionSnapshot snap = new RuleVersionSnapshot(1L, "scene", "t1", null,
                List.of(), List.of(), List.of(), RuleKind.EXPRESSION_SCRIPT.tag(), "R1", 1L,
                List.of(), List.of(), new ScriptSource("expr", "CEL"));
        EvalResult r = executor(new FakeEngine(null, true)).execute(snap, ctx());
        assertThat(r.errorCode()).isEqualTo(EvalErrorCode.SCRIPT_EVAL_ERROR.name());
    }

    @Test
    void unexpectedTypeYieldsScriptEvalError() {
        // 引擎返回非 Boolean/String/Number(此处 List)→走 switch default 分支→SCRIPT_EVAL_ERROR
        RuleVersionSnapshot snap = new RuleVersionSnapshot(1L, "scene", "t1", null,
                List.of(), List.of(), List.of(), RuleKind.EXPRESSION_SCRIPT.tag(), "R1", 1L,
                List.of(), List.of(), new ScriptSource("expr", "CEL"));
        EvalResult r = executor(new FakeEngine(List.of("x"))).execute(snap, ctx());
        assertThat(r.errorCode()).isEqualTo(EvalErrorCode.SCRIPT_EVAL_ERROR.name());
    }
}
