package com.sstlfsj.rule.benchmark;

import com.sstlfsj.rule.kernel.api.model.MetricDependency;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;
import com.sstlfsj.rule.kernel.api.model.ast.AndNode;
import org.eclipse.collections.api.map.primitive.MutableObjectIntMap;
import org.eclipse.collections.impl.map.mutable.primitive.ObjectIntHashMap;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * collectChosenVersions 热点 A/B：同 code 多版本取 max。
 * JDK LinkedHashMap&lt;String,Integer&gt;（装箱 + merge）vs eclipse ObjectIntHashMap&lt;String&gt;（无装箱）。
 * N 个候选，每个依赖 M=4 个 metric，版本在候选间错开以触发 max 合并。
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(1)
public class VersionMergeBenchmark {

    private static final int M = 4;

    @Param({"5", "20", "50"})
    public int n;

    private List<RuleVersionSnapshot> candidates;

    @Setup
    public void setup() {
        candidates = new java.util.ArrayList<>();
        for (int i = 0; i < n; i++) {
            java.util.List<MetricDependency> deps = new java.util.ArrayList<>();
            for (int m = 0; m < M; m++) {
                deps.add(new MetricDependency("metric_" + m, (i % 3) + 1)); // 版本 1..3 错开
            }
            candidates.add(new RuleVersionSnapshot((long) i, "scene", "1",
                    new AndNode(List.of(), null, null), List.of(), List.of(), List.of(),
                    "AST_BOOLEAN", deps));
        }
    }

    /** 当前实现风格：LinkedHashMap + Integer 装箱 + merge(Math::max)。 */
    @Benchmark
    public void jdkLinkedHashMap(Blackhole bh) {
        Map<String, Integer> chosen = new LinkedHashMap<>();
        for (RuleVersionSnapshot snap : candidates) {
            for (MetricDependency dep : snap.metricDependencies()) {
                chosen.merge(dep.metricCode(), dep.metricVersion(), Math::max);
            }
        }
        bh.consume(chosen);
    }

    /** eclipse 原始集合：ObjectIntHashMap，无装箱。 */
    @Benchmark
    public void eclipseObjectIntMap(Blackhole bh) {
        MutableObjectIntMap<String> chosen = new ObjectIntHashMap<>();
        for (RuleVersionSnapshot snap : candidates) {
            for (MetricDependency dep : snap.metricDependencies()) {
                int cur = chosen.getIfAbsent(dep.metricCode(), Integer.MIN_VALUE);
                if (dep.metricVersion() > cur) chosen.put(dep.metricCode(), dep.metricVersion());
            }
        }
        bh.consume(chosen);
    }
}
