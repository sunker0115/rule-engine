package com.sstlfsj.rule.kernel.evaluator;

import com.sstlfsj.rule.kernel.api.model.EvalContext;
import com.sstlfsj.rule.kernel.api.model.EventSource;
import com.sstlfsj.rule.kernel.api.model.RuleEvent;
import com.sstlfsj.rule.kernel.api.model.ast.AndNode;
import com.sstlfsj.rule.kernel.api.model.ast.AstNode;
import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;
import com.sstlfsj.rule.kernel.api.model.ast.DecisionLeafNode;
import com.sstlfsj.rule.kernel.api.model.ast.NotNode;
import com.sstlfsj.rule.kernel.api.model.ast.OrNode;
import com.sstlfsj.rule.kernel.api.model.ast.XorNode;
import com.sstlfsj.rule.kernel.api.spi.condition.ConditionEvaluator;
import com.sstlfsj.rule.kernel.internal.evaluator.AstCompiler;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AstCompilerTest {

    private static final String T = "ALWAYS_TRUE";
    private static final String F = "ALWAYS_FALSE";

    private final Map<String, ConditionEvaluator> evaluators = Map.of(
            T, (node, ctx) -> true,
            F, (node, ctx) -> false);

    private final AstCompiler compiler = new AstCompiler(evaluators);

    private ConditionNode t() { return new ConditionNode(T, null, null, Map.of(), 0.0); }
    private ConditionNode f() { return new ConditionNode(F, null, null, Map.of(), 0.0); }

    private EvalContext ctx() {
        RuleEvent event = new RuleEvent("t1", "scene1", "ORDER", "u1", "evt-1",
                Instant.now(), Map.of(), null, EventSource.HTTP);
        return new EvalContext("t1", event, null, Map.of(), Instant.parse("2026-06-01T00:00:00Z"));
    }

    @Test
    void condition_true() {
        assertThat(compiler.compile(t()).test(ctx())).isTrue();
    }

    @Test
    void condition_false() {
        assertThat(compiler.compile(f()).test(ctx())).isFalse();
    }

    @Test
    void and_allTrue_true_oneFalse_false() {
        assertThat(compiler.compile(new AndNode(List.of(t(), t(), t()), null, null)).test(ctx())).isTrue();
        assertThat(compiler.compile(new AndNode(List.of(t(), f(), t()), null, null)).test(ctx())).isFalse();
    }

    @Test
    void or_oneTrue_true_allFalse_false() {
        assertThat(compiler.compile(new OrNode(List.of(f(), t(), f()), null, null)).test(ctx())).isTrue();
        assertThat(compiler.compile(new OrNode(List.of(f(), f()), null, null)).test(ctx())).isFalse();
    }

    @Test
    void not_inverts() {
        assertThat(compiler.compile(new NotNode(t())).test(ctx())).isFalse();
        assertThat(compiler.compile(new NotNode(f())).test(ctx())).isTrue();
    }

    @Test
    void xor_exactlyOneTrue_true_else_false() {
        assertThat(compiler.compile(new XorNode(List.of(t(), f(), f()), null)).test(ctx())).isTrue();
        assertThat(compiler.compile(new XorNode(List.of(t(), t()), null)).test(ctx())).isFalse();
        assertThat(compiler.compile(new XorNode(List.of(f(), f()), null)).test(ctx())).isFalse();
    }

    @Test
    void nested_and_or() {
        // AND(true, OR(false, true)) = true
        AstNode ast = new AndNode(List.of(t(), new OrNode(List.of(f(), t()), null, null)), null, null);
        assertThat(compiler.compile(ast).test(ctx())).isTrue();
    }

    @Test
    void condition_noEvaluator_isFalse_notThrow() {
        // 镜像解释器：无算子 → ERROR → 不命中(false)，不抛
        ConditionNode unknown = new ConditionNode("UNKNOWN", "m1", null, Map.of(), 0.0);
        assertThat(compiler.compile(unknown).test(ctx())).isFalse();
    }

    @Test
    void nonBooleanNode_throwsIllegalArgument() {
        assertThatThrownBy(() -> compiler.compile(new DecisionLeafNode("BLOCK", "HIGH")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("DecisionLeafNode");
    }
}
