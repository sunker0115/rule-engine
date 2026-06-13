package com.sstlfsj.rule.benchmark;

import com.sstlfsj.rule.kernel.api.model.ConditionTypes;
import com.sstlfsj.rule.kernel.api.model.EvalContext;
import com.sstlfsj.rule.kernel.api.model.EventSource;
import com.sstlfsj.rule.kernel.api.model.MetricValue;
import com.sstlfsj.rule.kernel.api.model.RuleEvent;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;
import com.sstlfsj.rule.kernel.api.model.ast.AndNode;
import com.sstlfsj.rule.kernel.api.model.ast.AstNode;
import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;
import com.sstlfsj.rule.kernel.api.spi.condition.ConditionEvaluator;
import com.sstlfsj.rule.kernel.internal.condition.KernelEvaluators;
import com.sstlfsj.rule.kernel.internal.evaluator.AstCompiler;
import com.sstlfsj.rule.kernel.internal.evaluator.CompileErrorPolicy;
import com.sstlfsj.rule.kernel.internal.evaluator.CompiledExecutor;
import com.sstlfsj.rule.kernel.internal.evaluator.InterpretedExecutor;
import com.sstlfsj.rule.kernel.internal.evaluator.RuleVersionCache;
import com.sstlfsj.rule.kernel.internal.evaluator.TraceScope;
import org.openjdk.jmh.annotations.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * A/B 对照：同一 AST(AND N 个 GT 条件，冻结 LONG) + 同一 ctx 下，解释器 vs 编译执行器的
 * 非 trace 快路径耗时与分配。编译版预热缓存后稳定走 predicate.test。
 * 产出用于确认编译版的速度收益(省节点分派)与"分配 ≤ 解释器"判据。
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class CompiledVsInterpretedBenchmark {

    @Param({"5", "20", "50"})
    public int n;

    private InterpretedExecutor interpreter;
    private CompiledExecutor compiled;
    private RuleVersionSnapshot snapshot;
    private EvalContext ctx;

    @Setup
    public void setup() {
        Map<String, ConditionEvaluator> evaluators = KernelEvaluators.defaults();
        interpreter = new InterpretedExecutor(evaluators);
        compiled = new CompiledExecutor(interpreter, new AstCompiler(evaluators), new RuleVersionCache(),
                true, Set.of(), CompileErrorPolicy.FALLBACK);

        List<AstNode> conds = new ArrayList<>();
        Map<String, MetricValue> metrics = new HashMap<>();
        for (int i = 0; i < n; i++) {
            String mc = "m" + i;
            conds.add(new ConditionNode(ConditionTypes.GT, mc, null, Map.of("threshold", 0L), 0.0, "LONG"));
            metrics.put(mc, new MetricValue(1L, "LONG", "PROVIDED"));
        }
        AstNode ast = new AndNode(conds, null, null);
        snapshot = new RuleVersionSnapshot(1L, "scene", "t1", ast, null, null, null,
                "AST_BOOLEAN", "RULE_A", 1L, List.of(), List.of());
        RuleEvent event = new RuleEvent("t1", "scene", "ORDER", "sub1", "evt-1",
                Instant.now(), Map.of(), Map.of(), EventSource.HTTP);
        ctx = new EvalContext("t1", event, null, metrics, Instant.now());
    }

    @Benchmark
    public boolean interpreted() throws Exception {
        return ScopedValue.where(TraceScope.COLLECT, false)
                .call(() -> interpreter.execute(snapshot, ctx).ruleHit());
    }

    @Benchmark
    public boolean compiled() throws Exception {
        return ScopedValue.where(TraceScope.COLLECT, false)
                .call(() -> compiled.execute(snapshot, ctx).ruleHit());
    }
}
