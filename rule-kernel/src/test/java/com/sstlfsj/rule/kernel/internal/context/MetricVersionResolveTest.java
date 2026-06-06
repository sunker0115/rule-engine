package com.sstlfsj.rule.kernel.internal.context;

import com.sstlfsj.rule.kernel.api.model.*;
import com.sstlfsj.rule.kernel.api.spi.metric.MetricDefinitionResolver;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** 评估期按规则绑定版本解析定义；过渡期同 code 多版本取最高版本。 */
class MetricVersionResolveTest {

    private static final Instant NOW = Instant.parse("2026-06-06T00:00:00Z");

    /** 构造含 providedMetrics 的 RuleEvent，与 EvalContextAssemblerFetchTest 保持一致。 */
    private RuleEvent event(Map<String, Object> provided) {
        return new RuleEvent("1", "PAY", "transfer", "u1", "e1", NOW, Map.of("amt", 500), provided);
    }

    @Test
    void resolvesBoundVersion_singleVersionSteadyState() {
        AtomicInteger seenVersion = new AtomicInteger(-1);
        MetricDefinitionResolver resolver = (t, code, ver) -> {
            seenVersion.set(ver);
            // allowProvided=true，直接采信 provided 值，避免走 handler fetch
            return new MetricDescriptor(code, ver, "ATTRIBUTE", "LONG", true, 0, Map.of());
        };
        EvalContextAssembler asm = new EvalContextAssembler(
                List.of(), Map.of(), resolver, null, null, 0L);

        RuleVersionSnapshot rule = RuleVersionSnapshot.builder()
                .ruleVersionId(1L).tenantId("1").sceneCode("PAY").conditionAst(null)
                .addMetricDependency("account.age", 3).build();

        // account.age 有 provided 值且 allowProvided=true，直接采信，resolver 仅被调用验证版本号
        asm.assemble(event(Map.of("account.age", 5)), List.of(rule), NOW);

        assertThat(seenVersion.get()).isEqualTo(3);
    }

    @Test
    void resolvesBoundVersion_contextHasMetric() {
        MetricDefinitionResolver resolver = (t, code, ver) ->
                new MetricDescriptor(code, ver, "ATTRIBUTE", "LONG", true, 0, Map.of());
        EvalContextAssembler asm = new EvalContextAssembler(
                List.of(), Map.of(), resolver, null, null, 0L);

        RuleVersionSnapshot rule = RuleVersionSnapshot.builder()
                .ruleVersionId(1L).tenantId("1").sceneCode("PAY").conditionAst(null)
                .addMetricDependency("account.age", 3).build();

        EvalContext ctx = asm.assemble(event(Map.of("account.age", 5)), List.of(rule), NOW);

        assertThat(ctx.hasMetric("account.age")).isTrue();
    }

    @Test
    void multiVersionTransition_picksHighest() {
        AtomicInteger seenVersion = new AtomicInteger(-1);
        MetricDefinitionResolver resolver = (t, code, ver) -> {
            seenVersion.set(ver);
            return new MetricDescriptor(code, ver, "ATTRIBUTE", "LONG", true, 0, Map.of());
        };
        EvalContextAssembler asm = new EvalContextAssembler(
                List.of(), Map.of(), resolver, null, null, 0L);

        RuleVersionSnapshot ruleA = RuleVersionSnapshot.builder()
                .ruleVersionId(1L).tenantId("1").sceneCode("PAY").conditionAst(null)
                .addMetricDependency("account.age", 1).build();
        RuleVersionSnapshot ruleB = RuleVersionSnapshot.builder()
                .ruleVersionId(2L).tenantId("1").sceneCode("PAY").conditionAst(null)
                .addMetricDependency("account.age", 2).build();

        asm.assemble(event(Map.of("account.age", 5)), List.of(ruleA, ruleB), NOW);

        // 同 code 两个版本（1 和 2），取最高版本 2 解析
        assertThat(seenVersion.get()).isEqualTo(2);
    }
}
