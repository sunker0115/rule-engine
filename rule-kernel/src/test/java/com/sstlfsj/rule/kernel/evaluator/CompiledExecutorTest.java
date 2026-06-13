package com.sstlfsj.rule.kernel.evaluator;

import com.sstlfsj.rule.kernel.api.model.EvalContext;
import com.sstlfsj.rule.kernel.api.model.EvalResult;
import com.sstlfsj.rule.kernel.api.model.EventSource;
import com.sstlfsj.rule.kernel.api.model.RuleEvent;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;
import com.sstlfsj.rule.kernel.api.model.ast.AndNode;
import com.sstlfsj.rule.kernel.api.model.ast.AstNode;
import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;
import com.sstlfsj.rule.kernel.api.model.ast.DecisionLeafNode;
import com.sstlfsj.rule.kernel.api.spi.condition.ConditionEvaluator;
import com.sstlfsj.rule.kernel.internal.evaluator.AstCompiler;
import com.sstlfsj.rule.kernel.internal.evaluator.CompileErrorPolicy;
import com.sstlfsj.rule.kernel.internal.evaluator.CompiledExecutor;
import com.sstlfsj.rule.kernel.internal.evaluator.InterpretedExecutor;
import com.sstlfsj.rule.kernel.internal.evaluator.RuleVersionCache;
import com.sstlfsj.rule.kernel.internal.evaluator.TraceScope;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CompiledExecutorTest {

    private static final String T = "ALWAYS_TRUE";
    private static final String F = "ALWAYS_FALSE";
    private final Map<String, ConditionEvaluator> evaluators = Map.of(
            T, (node, ctx) -> true, F, (node, ctx) -> false);
    private final InterpretedExecutor interpreter = new InterpretedExecutor(evaluators);
    private final AstCompiler compiler = new AstCompiler(evaluators);

    // 区分"走编译"vs"委托解释器"的信号：编译路径会 populate 此 cache，委托路径不会。
    private final RuleVersionCache cache = new RuleVersionCache();

    private ConditionNode t() { return new ConditionNode(T, null, null, Map.of(), 0.0); }
    private ConditionNode f() { return new ConditionNode(F, null, null, Map.of(), 0.0); }

    private RuleVersionSnapshot snap(AstNode ast) {
        return snap(1L, ast);
    }

    private RuleVersionSnapshot snap(long ruleVersionId, AstNode ast) {
        return new RuleVersionSnapshot(ruleVersionId, "scene1", "t1", ast, null, null, null,
                "AST_BOOLEAN", "RULE_A", 1L, List.of(), List.of());
    }

    private EvalContext ctx() {
        RuleEvent event = new RuleEvent("t1", "scene1", "ORDER", "u1", "evt-1",
                Instant.now(), Map.of(), null, EventSource.HTTP);
        return new EvalContext("t1", event, null, Map.of(), Instant.parse("2026-06-01T00:00:00Z"));
    }

    private CompiledExecutor executor(boolean enabled, Set<String> whitelist, CompileErrorPolicy policy) {
        return new CompiledExecutor(interpreter, compiler, cache, enabled, whitelist, policy);
    }

    @Test
    void disabled_delegatesToInterpreter() {
        // 关开关：委托解释器(COLLECT 未绑定默认 true → 产 trace)，cache 不被填充
        EvalResult r = executor(false, Set.of(), CompileErrorPolicy.FALLBACK)
                .execute(snap(t()), ctx());
        assertThat(r.ruleHit()).isTrue();
        assertThat(r.nodeTrace()).isNotEmpty();
        assertThat(cache.size()).isZero();
    }

    @Test
    void enabled_nonTrace_usesCompiled() throws Exception {
        CompiledExecutor exec = executor(true, Set.of(), CompileErrorPolicy.FALLBACK);
        EvalResult hit = ScopedValue.where(TraceScope.COLLECT, false)
                .call(() -> exec.execute(snap(new AndNode(List.of(t(), t()), null, null)), ctx()));
        assertThat(hit.ruleHit()).isTrue();
        assertThat(hit.nodeTrace()).isEmpty();
        // 走编译路径：cache 被填充
        assertThat(cache.size()).isEqualTo(1);

        // 不同 AST 必须用不同 ruleVersionId(缓存键)，否则命中前一条的编译产物
        EvalResult miss = ScopedValue.where(TraceScope.COLLECT, false)
                .call(() -> exec.execute(snap(2L, new AndNode(List.of(t(), f()), null, null)), ctx()));
        assertThat(miss.ruleHit()).isFalse();
        assertThat(miss.nodeTrace()).isEmpty();
    }

    @Test
    void enabled_traceMode_delegatesToInterpreter() throws Exception {
        // 开 trace(COLLECT=true)：回落解释器产 NodeTrace，cache 不被填充
        CompiledExecutor exec = executor(true, Set.of(), CompileErrorPolicy.FALLBACK);
        EvalResult r = ScopedValue.where(TraceScope.COLLECT, true)
                .call(() -> exec.execute(snap(t()), ctx()));
        assertThat(r.ruleHit()).isTrue();
        assertThat(r.nodeTrace()).isNotEmpty();
        assertThat(cache.size()).isZero();
    }

    @Test
    void whitelist_nonMatching_delegatesToInterpreter() throws Exception {
        // 白名单非空且不含本规则 code：委托解释器，cache 不被填充
        CompiledExecutor exec = executor(true, Set.of("OTHER_RULE"), CompileErrorPolicy.FALLBACK);
        EvalResult r = ScopedValue.where(TraceScope.COLLECT, false)
                .call(() -> exec.execute(snap(t()), ctx()));
        assertThat(r.ruleHit()).isTrue();
        assertThat(cache.size()).isZero();
    }

    @Test
    void whitelist_matching_usesCompiled() throws Exception {
        CompiledExecutor exec = executor(true, Set.of("RULE_A"), CompileErrorPolicy.FALLBACK);
        EvalResult r = ScopedValue.where(TraceScope.COLLECT, false)
                .call(() -> exec.execute(snap(t()), ctx()));
        assertThat(r.ruleHit()).isTrue();
        assertThat(cache.size()).isEqualTo(1);
    }

    @Test
    void compileError_fallback_delegatesToInterpreter() throws Exception {
        // FALLBACK 接住编译异常后转交解释器；解释器对 DecisionLeafNode 本就抛 IllegalState
        CompiledExecutor exec = executor(true, Set.of(), CompileErrorPolicy.FALLBACK);
        assertThatThrownBy(() -> ScopedValue.where(TraceScope.COLLECT, false)
                .call(() -> exec.execute(snap(new DecisionLeafNode("BLOCK", "HIGH")), ctx())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DecisionLeafNode");
    }

    @Test
    void compileError_fail_throwsIllegalStateWithRuleVersionId() throws Exception {
        CompiledExecutor exec = executor(true, Set.of(), CompileErrorPolicy.FAIL);
        assertThatThrownBy(() -> ScopedValue.where(TraceScope.COLLECT, false)
                .call(() -> exec.execute(snap(new DecisionLeafNode("BLOCK", "HIGH")), ctx())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ruleVersionId=1");
    }
}
