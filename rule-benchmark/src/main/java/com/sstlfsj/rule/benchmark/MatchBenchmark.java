package com.sstlfsj.rule.benchmark;

import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;
import com.sstlfsj.rule.kernel.api.model.ast.AndNode;
import com.sstlfsj.rule.kernel.internal.index.SceneRuleIndex;
import org.eclipse.collections.impl.set.mutable.primitive.LongHashSet;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 候选合并/去重基准：对比 match() 线性去重、平方级去重，以及 HashSet&lt;Long&gt; vs LongHashSet。
 * exact 与 wildcard 桶各 N 条，半数 id 重叠（触发去重路径）。
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(1)
public class MatchBenchmark {

    @Param({"5", "20", "50"})
    public int n;

    private SceneRuleIndex index;
    private List<RuleVersionSnapshot> exact;
    private List<RuleVersionSnapshot> wildcard;

    @Setup
    public void setup() {
        index = new SceneRuleIndex();
        exact = new ArrayList<>();
        wildcard = new ArrayList<>();
        for (int i = 0; i < n; i++) exact.add(snap((long) i));
        // wildcard 后半段 id 与 exact 重叠，前半段是新 id
        for (int i = 0; i < n; i++) wildcard.add(snap((long) (i + n / 2)));
        index.update("t1", "scene", "ORDER", exact);
        index.update("t1", "scene", "*", wildcard);
    }

    private static RuleVersionSnapshot snap(Long id) {
        return new RuleVersionSnapshot(id, "scene", "t1",
                new AndNode(List.of(), null, null), List.of(), List.of(), List.of(), "AST_BOOLEAN");
    }

    /** 当前实现：Set 去重，线性。 */
    @Benchmark
    public List<RuleVersionSnapshot> match_linear() {
        return index.match("t1", "scene", "ORDER");
    }

    /** 旧实现复刻：stream().noneMatch 套循环，平方级。 */
    @Benchmark
    public List<RuleVersionSnapshot> match_quadratic() {
        List<RuleVersionSnapshot> merged = new ArrayList<>(exact);
        for (RuleVersionSnapshot snap : wildcard) {
            if (exact.stream().noneMatch(s -> s.ruleVersionId().equals(snap.ruleVersionId()))) {
                merged.add(snap);
            }
        }
        return merged;
    }

    /** 去重数据结构 A/B：JDK HashSet&lt;Long&gt;（装箱）。 */
    @Benchmark
    public void dedup_hashSetLong(Blackhole bh) {
        Set<Long> seen = new HashSet<>(exact.size() * 2);
        for (RuleVersionSnapshot s : exact) seen.add(s.ruleVersionId());
        int extra = 0;
        for (RuleVersionSnapshot s : wildcard) if (seen.add(s.ruleVersionId())) extra++;
        bh.consume(extra);
    }

    /** 去重数据结构 A/B：eclipse LongHashSet（无装箱）。 */
    @Benchmark
    public void dedup_eclipseLongSet(Blackhole bh) {
        LongHashSet seen = new LongHashSet(exact.size() * 2);
        for (RuleVersionSnapshot s : exact) seen.add(s.ruleVersionId());
        int extra = 0;
        for (RuleVersionSnapshot s : wildcard) if (seen.add(s.ruleVersionId())) extra++;
        bh.consume(extra);
    }
}
