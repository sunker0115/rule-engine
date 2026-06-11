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
 * 评估全路径基准（provided metrics、无 SQL/HTTP I/O）：match → pre-gate → assemble → execute。
 * 每个候选依赖 3 个共享 metric（触发 collectChosenVersions 合并），全部由 providedMetrics 命中。
 * resolver 返回预建 descriptor 模拟 Caffeine 命中，隔离 DB 成本，只量引擎 CPU。
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(1)
public class EvalEngineBenchmark {

    private static final List<String> METRIC_CODES = List.of("m0", "m1", "m2");

    @Param({"1", "5", "20", "50"})
    public int n;

    @Param({"HIGHEST_PRIORITY", "FIRST_HIT"})
    public String strategy;

    private EvalEngine engine;
    private RuleEvent event;

    @Setup
    public void setup() {
        SceneRuleIndex index = new SceneRuleIndex();
        List<RuleVersionSnapshot> snaps = new ArrayList<>();
        List<MetricDependency> deps = METRIC_CODES.stream()
                .map(c -> new MetricDependency(c, 1)).toList();
        for (int i = 0; i < n; i++) {
            snaps.add(new RuleVersionSnapshot((long) i, "scene", "1",
                    new AndNode(List.of(), null, null), List.of(),
                    List.of(new RuleVersionSnapshot.DecisionBinding("D" + i, i)),
                    List.of(), "AST_BOOLEAN", null, 0L, deps, List.of()));
        }
        index.update("1", "scene", "ORDER", snaps);
        index.setStrategy("1", "scene", SceneExecutionStrategy.valueOf(strategy));

        Map<String, MetricDescriptor> descriptors = new HashMap<>();
        for (String c : METRIC_CODES) {
            descriptors.put(c, new MetricDescriptor(c, 1, "PROVIDED", "LONG", true, 0, Map.of()));
        }
        MetricDefinitionResolver resolver = (tenant, code, version) -> descriptors.get(code);

        EvalContextAssembler asm = new EvalContextAssembler(
                List.of(), Map.of(), resolver, null, null, 0L);

        // 总是命中的 executor：取 decisionBindings 最高优先级
        RuleVersionExecutor exec = (snap, ctx) -> {
            RuleVersionSnapshot.DecisionBinding b = snap.decisionBindings().get(0);
            Decision d = new Decision(b.decisionCode(), "", b.priority(), snap.ruleVersionId());
            return new EvalResult(true, d, List.of(d), List.of(), null, List.of(), null, null, null);
        };
        engine = new EvalEngine(index, asm, Map.of(), Map.of("AST_BOOLEAN", exec), false);

        Map<String, Object> provided = new HashMap<>();
        for (String c : METRIC_CODES) provided.put(c, 1L);
        event = new RuleEvent("1", "scene", "ORDER", "sub1", "evt-1",
                Instant.now(), Map.of(), provided, com.sstlfsj.rule.kernel.api.model.EventSource.HTTP);
    }

    @Benchmark
    public EvalOutcome evaluate() {
        return engine.evaluateWithContext(event, engine.match(event), Instant.now());
    }
}
