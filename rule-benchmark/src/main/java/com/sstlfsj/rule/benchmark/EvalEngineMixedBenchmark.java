package com.sstlfsj.rule.benchmark;

import com.sstlfsj.rule.kernel.api.model.*;
import com.sstlfsj.rule.kernel.api.model.ast.AndNode;
import com.sstlfsj.rule.kernel.api.spi.executor.RuleVersionExecutor;
import com.sstlfsj.rule.kernel.api.spi.metric.MetricDefinitionResolver;
import com.sstlfsj.rule.kernel.internal.context.EvalContextAssembler;
import com.sstlfsj.rule.kernel.internal.engine.EvalEngine;
import com.sstlfsj.rule.kernel.internal.index.SceneRuleIndex;
import org.openjdk.jmh.annotations.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 混合场景 baseline：20 条规则中 N 条带模拟计算负载（代表 EXPRESSION_SCRIPT/DECISION_FLOW），
 * 其余为纯 AST_BOOLEAN 轻量规则。PARALLEL vs SEQUENTIAL 在 0/5/10/20 条重规则下的吞吐对比。
 *
 * 预期：纯轻量（heavyCount=0）PARALLEL 负优化；重规则 ≥5 时 PARALLEL 开始收益。
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(1)
public class EvalEngineMixedBenchmark {

    private static final AndNode EMPTY_AST = new AndNode(List.of(), null, null);
    private static final int TOTAL = 20;
    /** 模拟一次脚本求值/Flow 遍历的迭代次数（~1-5µs，可调）。 */
    private static final int HEAVY_WORK = 10000;

    @Param({"0", "5", "10", "20"})
    public int heavyCount;

    @Param({"SEQUENTIAL", "PARALLEL"})
    public String mode;

    private EvalEngine engine;
    private RuleEvent event;

    @Setup
    public void setup() {
        SceneRuleIndex index = new SceneRuleIndex();
        List<RuleVersionSnapshot> snaps = new ArrayList<>();
        for (int i = 0; i < TOTAL; i++) {
            snaps.add(new RuleVersionSnapshot((long) i, "scene", "1",
                    EMPTY_AST, List.of(),
                    List.of(new RuleVersionSnapshot.DecisionBinding("D" + i, i)),
                    List.of(), "AST_BOOLEAN", null, 0L,
                    List.of(), List.of()));
        }
        index.update("1", "scene", "ORDER", snaps);
        index.setStrategy("1", "scene", SceneExecutionStrategy.HIGHEST_PRIORITY);
        index.setDefaultParams("1", "scene", Map.of("executionMode", mode));

        RuleVersionExecutor mixed = (snap, ctx) -> {
            RuleVersionSnapshot.DecisionBinding b = snap.decisionBindings().get(0);
            // 前 heavyCount 条规则模拟重负载（脚本求值/Flow 遍历），其余为轻量
            if (snap.ruleVersionId() < heavyCount) {
                long x = 0;
                for (int j = 0; j < HEAVY_WORK; j++) x = x * 31 + j;
                Decision d = new Decision(b.decisionCode(), "", b.priority(), snap.ruleVersionId());
                return new EvalResult(x > 0, d, List.of(d), List.of(), null, null, null, null);
            }
            Decision d = new Decision(b.decisionCode(), "", b.priority(), snap.ruleVersionId());
            return new EvalResult(true, d, List.of(d), List.of(), null, null, null, null);
        };

        Map<String, MetricDescriptor> descriptors = new HashMap<>();
        MetricDefinitionResolver resolver = (tenant, code, version) -> null; // 无 metric 依赖

        EvalContextAssembler asm = new EvalContextAssembler(
                List.of(), Map.of(), resolver, null, null, 0L);

        engine = new EvalEngine(index, asm, Map.of(), Map.of("AST_BOOLEAN", mixed), false);

        event = new RuleEvent("1", "scene", "ORDER", "sub1", "evt-1",
                Instant.now(), Map.of(), Map.of(), EventSource.HTTP);
    }

    @Benchmark
    public EvalOutcome evaluate() {
        return engine.evaluateWithContext(event, engine.match(event), Instant.now());
    }
}
