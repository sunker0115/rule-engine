package com.sstlfsj.rule.kernel.internal.context;

import com.sstlfsj.rule.kernel.api.model.*;
import com.sstlfsj.rule.kernel.api.model.ast.AstNode;
import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;
import com.sstlfsj.rule.kernel.api.spi.metric.MetricCache;
import com.sstlfsj.rule.kernel.api.spi.metric.MetricDefinitionResolver;
import com.sstlfsj.rule.kernel.api.spi.metric.MetricSourceHandler;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class EvalContextAssemblerFetchTest {

    private static final Instant NOW = Instant.parse("2026-06-06T00:00:00Z");

    private RuleEvent event(Map<String, Object> provided) {
        return new RuleEvent("1", "PAY", "transfer", "u1", "e1", NOW, Map.of("amt", 500), provided);
    }

    private RuleVersionSnapshot snapWithDep(String metricCode) {
        AstNode ast = new ConditionNode("GT", metricCode, null, Map.of("threshold", 100), null, "LONG");
        return RuleVersionSnapshot.builder()
                .ruleVersionId(1L).sceneCode("PAY").tenantId("1").conditionAst(ast)
                .addMetricDependency(metricCode).build();
    }

    private MetricDescriptor sqlDef(String code, boolean allowProvided, int ttl) {
        return new MetricDescriptor(code, "SQL_AGGREGATE", "LONG", allowProvided, ttl, Map.of());
    }

    @Test
    void fetch_routesBySourceType_andStoresFetched() {
        MetricSourceHandler handler = q -> new MetricValue(999L, "LONG", "FETCHED");
        MetricDefinitionResolver resolver = (t, c) -> sqlDef(c, false, 0);
        EvalContextAssembler asm = new EvalContextAssembler(
                List.of(), Map.of("SQL_AGGREGATE", handler), resolver, null, Runnable::run, 1000L);

        EvalContext ctx = asm.assemble(event(Map.of()), List.of(snapWithDep("balance")), NOW);

        MetricValue mv = ctx.getMetric("balance");
        assertThat(mv.value()).isEqualTo(999L);
        assertThat(mv.valueSource()).isEqualTo("FETCHED");
        assertThat(mv.isError()).isFalse();
    }

    @Test
    void providedPriority_skipsFetch_whenAllowProvidedTrue() {
        AtomicInteger calls = new AtomicInteger();
        MetricSourceHandler handler = q -> { calls.incrementAndGet(); return new MetricValue(1L, "LONG", "FETCHED"); };
        MetricDefinitionResolver resolver = (t, c) -> sqlDef(c, true, 0);
        EvalContextAssembler asm = new EvalContextAssembler(
                List.of(), Map.of("SQL_AGGREGATE", handler), resolver, null, Runnable::run, 1000L);

        EvalContext ctx = asm.assemble(event(Map.of("balance", 7L)), List.of(snapWithDep("balance")), NOW);

        assertThat(ctx.getMetric("balance").value()).isEqualTo(7L);
        assertThat(ctx.getMetric("balance").valueSource()).isEqualTo("PROVIDED");
        assertThat(calls.get()).isZero();
    }

    @Test
    void providedIgnored_whenAllowProvidedFalse_thenFetched() {
        MetricSourceHandler handler = q -> new MetricValue(42L, "LONG", "FETCHED");
        MetricDefinitionResolver resolver = (t, c) -> sqlDef(c, false, 0);
        EvalContextAssembler asm = new EvalContextAssembler(
                List.of(), Map.of("SQL_AGGREGATE", handler), resolver, null, Runnable::run, 1000L);

        EvalContext ctx = asm.assemble(event(Map.of("balance", 7L)), List.of(snapWithDep("balance")), NOW);

        assertThat(ctx.getMetric("balance").value()).isEqualTo(42L);
        assertThat(ctx.getMetric("balance").valueSource()).isEqualTo("FETCHED");
    }

    @Test
    void handlerThrows_degradesToError() {
        MetricSourceHandler handler = q -> { throw new RuntimeException("db down"); };
        MetricDefinitionResolver resolver = (t, c) -> sqlDef(c, false, 0);
        EvalContextAssembler asm = new EvalContextAssembler(
                List.of(), Map.of("SQL_AGGREGATE", handler), resolver, null, Runnable::run, 1000L);

        EvalContext ctx = asm.assemble(event(Map.of()), List.of(snapWithDep("balance")), NOW);

        MetricValue mv = ctx.getMetric("balance");
        assertThat(mv.isError()).isTrue();
        assertThat(mv.errorCode()).isEqualTo("METRIC_FETCH_FAIL");
    }

    @Test
    void missingHandlerForSourceType_degradesToError() {
        MetricDefinitionResolver resolver = (t, c) -> sqlDef(c, false, 0);
        EvalContextAssembler asm = new EvalContextAssembler(
                List.of(), Map.of(), resolver, null, Runnable::run, 1000L);

        EvalContext ctx = asm.assemble(event(Map.of()), List.of(snapWithDep("balance")), NOW);

        assertThat(ctx.getMetric("balance").isError()).isTrue();
    }

    @Test
    void cacheHit_skipsFetch() {
        AtomicInteger calls = new AtomicInteger();
        MetricSourceHandler handler = q -> { calls.incrementAndGet(); return new MetricValue(1L, "LONG", "FETCHED"); };
        MetricDefinitionResolver resolver = (t, c) -> sqlDef(c, false, 60);
        MetricValue cached = new MetricValue(500L, "LONG", "FETCHED");
        MetricCache cache = new MetricCache() {
            public MetricValue get(String key) { return cached; }
            public void put(String key, MetricValue value, int ttlSeconds) { }
        };
        EvalContextAssembler asm = new EvalContextAssembler(
                List.of(), Map.of("SQL_AGGREGATE", handler), resolver, cache, Runnable::run, 1000L);

        EvalContext ctx = asm.assemble(event(Map.of()), List.of(snapWithDep("balance")), NOW);

        assertThat(ctx.getMetric("balance").value()).isEqualTo(500L);
        assertThat(calls.get()).isZero();
    }

    @Test
    void queryCarriesNow() {
        Instant[] seen = new Instant[1];
        MetricSourceHandler handler = q -> { seen[0] = q.now(); return new MetricValue(1L, "LONG", "FETCHED"); };
        MetricDefinitionResolver resolver = (t, c) -> sqlDef(c, false, 0);
        EvalContextAssembler asm = new EvalContextAssembler(
                List.of(), Map.of("SQL_AGGREGATE", handler), resolver, null, Runnable::run, 1000L);

        asm.assemble(event(Map.of()), List.of(snapWithDep("balance")), NOW);

        assertThat(seen[0]).isEqualTo(NOW);
    }
}
