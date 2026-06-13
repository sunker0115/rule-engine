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
import com.sstlfsj.rule.kernel.internal.condition.KernelEvaluators;
import com.sstlfsj.rule.kernel.internal.evaluator.InterpretedExecutor;
import com.sstlfsj.rule.kernel.internal.evaluator.TraceScope;
import org.openjdk.jmh.annotations.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Phase 0 闸：仅量 InterpretedExecutor.execute() 纯 AST 求值耗时（非 trace 快路径）。
 * AST = AND(N 个 GT 条件)，dataType 冻结为 LONG（代表生产发布期冻结后的类型，走 LongComparisonStrategy
 * 整型快路径，而非 DSL 未冻结的 BigDecimal 路径），全部 provided metric 命中。
 * 产出 ns/op + 每 op 分配，对照生产端到端时延估算 AST 求值占比，决定 §2.13 是否值得做。
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class InterpretedExecBenchmark {

    @Param({"5", "20", "50"})
    public int n;

    private InterpretedExecutor executor;
    private RuleVersionSnapshot snapshot;
    private EvalContext ctx;

    @Setup
    public void setup() {
        executor = new InterpretedExecutor(KernelEvaluators.defaults());
        List<AstNode> conds = new ArrayList<>();
        Map<String, MetricValue> metrics = new HashMap<>();
        for (int i = 0; i < n; i++) {
            String mc = "m" + i;
            conds.add(new ConditionNode(ConditionTypes.GT, mc, null, Map.of("threshold", 0L), 0.0, "LONG"));
            metrics.put(mc, new MetricValue(1L, "LONG", "PROVIDED"));
        }
        AstNode ast = new AndNode(conds, null, null);
        snapshot = new RuleVersionSnapshot(1L, "scene", "t1", ast, null, null, null, "AST_BOOLEAN");
        RuleEvent event = new RuleEvent("t1", "scene", "ORDER", "sub1", "evt-1",
                Instant.now(), Map.of(), Map.of(), EventSource.HTTP);
        ctx = new EvalContext("t1", event, null, metrics, Instant.now());
    }

    @Benchmark
    public boolean executeFastPath() throws Exception {
        return ScopedValue.where(TraceScope.COLLECT, false)
                .call(() -> executor.execute(snapshot, ctx).ruleHit());
    }
}
