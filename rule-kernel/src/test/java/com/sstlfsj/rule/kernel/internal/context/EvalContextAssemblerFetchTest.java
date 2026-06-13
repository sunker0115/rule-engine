package com.sstlfsj.rule.kernel.internal.context;

import com.sstlfsj.rule.kernel.api.model.*;
import com.sstlfsj.rule.kernel.api.model.ast.AstNode;
import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;
import com.sstlfsj.rule.kernel.api.spi.metric.MetricCache;
import com.sstlfsj.rule.kernel.api.spi.metric.MetricDefinitionResolver;
import com.sstlfsj.rule.kernel.api.spi.metric.MetricSourceHandler;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class EvalContextAssemblerFetchTest {

    private static final Instant NOW = Instant.parse("2026-06-06T00:00:00Z");

    private static final ExecutorService EXEC = Executors.newVirtualThreadPerTaskExecutor();

    @AfterAll
    static void closeExec() { EXEC.shutdown(); }

    private RuleEvent event(Map<String, Object> provided) {
        return new RuleEvent("1", "PAY", "transfer", "u1", "e1", NOW, Map.of("amt", 500), provided, com.sstlfsj.rule.kernel.api.model.EventSource.HTTP);
    }

    private RuleVersionSnapshot snapWithDep(String metricCode) {
        AstNode ast = new ConditionNode("GT", metricCode, null, Map.of("threshold", 100), null, "LONG");
        return RuleVersionSnapshot.builder()
                .ruleVersionId(1L).sceneCode("PAY").tenantId("1").conditionAst(ast)
                .addMetricDependency(metricCode, 1).build();
    }

    private MetricDescriptor sqlDef(String code, boolean allowProvided, int ttl) {
        return new MetricDescriptor(code, 1, "SQL_AGGREGATE", "LONG", allowProvided, ttl, Map.of());
    }

    @Test
    void fetch_routesBySourceType_andStoresFetched() {
        MetricSourceHandler handler = q -> new MetricValue(999L, "LONG", "FETCHED");
        MetricDefinitionResolver resolver = (t, c, v) -> sqlDef(c, false, 0);
        EvalContextAssembler asm = new EvalContextAssembler(
                List.of(), Map.of("SQL_AGGREGATE", handler), resolver, null, EXEC,1000L);

        EvalContext ctx = asm.assemble(event(Map.of()), List.of(snapWithDep("balance")), new EvalEnv(NOW, java.util.Map.of()));

        MetricValue mv = ctx.getMetric("balance");
        assertThat(mv.value()).isEqualTo(999L);
        assertThat(mv.valueSource()).isEqualTo("FETCHED");
        assertThat(mv.isError()).isFalse();
    }

    @Test
    void providedPriority_skipsFetch_whenAllowProvidedTrue() {
        AtomicInteger calls = new AtomicInteger();
        MetricSourceHandler handler = q -> { calls.incrementAndGet(); return new MetricValue(1L, "LONG", "FETCHED"); };
        MetricDefinitionResolver resolver = (t, c, v) -> sqlDef(c, true, 0);
        EvalContextAssembler asm = new EvalContextAssembler(
                List.of(), Map.of("SQL_AGGREGATE", handler), resolver, null, EXEC,1000L);

        EvalContext ctx = asm.assemble(event(Map.of("balance", 7L)), List.of(snapWithDep("balance")), new EvalEnv(NOW, java.util.Map.of()));

        assertThat(ctx.getMetric("balance").value()).isEqualTo(7L);
        assertThat(ctx.getMetric("balance").valueSource()).isEqualTo("PROVIDED");
        assertThat(calls.get()).isZero();
    }

    @Test
    void providedIgnored_whenAllowProvidedFalse_thenFetched() {
        MetricSourceHandler handler = q -> new MetricValue(42L, "LONG", "FETCHED");
        MetricDefinitionResolver resolver = (t, c, v) -> sqlDef(c, false, 0);
        EvalContextAssembler asm = new EvalContextAssembler(
                List.of(), Map.of("SQL_AGGREGATE", handler), resolver, null, EXEC,1000L);

        EvalContext ctx = asm.assemble(event(Map.of("balance", 7L)), List.of(snapWithDep("balance")), new EvalEnv(NOW, java.util.Map.of()));

        assertThat(ctx.getMetric("balance").value()).isEqualTo(42L);
        assertThat(ctx.getMetric("balance").valueSource()).isEqualTo("FETCHED");
    }

    @Test
    void handlerThrows_degradesToError() {
        MetricSourceHandler handler = q -> { throw new RuntimeException("db down"); };
        MetricDefinitionResolver resolver = (t, c, v) -> sqlDef(c, false, 0);
        EvalContextAssembler asm = new EvalContextAssembler(
                List.of(), Map.of("SQL_AGGREGATE", handler), resolver, null, EXEC,1000L);

        EvalContext ctx = asm.assemble(event(Map.of()), List.of(snapWithDep("balance")), new EvalEnv(NOW, java.util.Map.of()));

        MetricValue mv = ctx.getMetric("balance");
        assertThat(mv.isError()).isTrue();
        assertThat(mv.errorCode()).isEqualTo("METRIC_FETCH_FAIL");
    }

    @Test
    void missingHandlerForSourceType_degradesToError() {
        MetricDefinitionResolver resolver = (t, c, v) -> sqlDef(c, false, 0);
        EvalContextAssembler asm = new EvalContextAssembler(
                List.of(), Map.of(), resolver, null, EXEC,1000L);

        EvalContext ctx = asm.assemble(event(Map.of()), List.of(snapWithDep("balance")), new EvalEnv(NOW, java.util.Map.of()));

        assertThat(ctx.getMetric("balance").isError()).isTrue();
    }

    @Test
    void cacheHit_skipsFetch() {
        AtomicInteger calls = new AtomicInteger();
        MetricSourceHandler handler = q -> { calls.incrementAndGet(); return new MetricValue(1L, "LONG", "FETCHED"); };
        MetricDefinitionResolver resolver = (t, c, v) -> sqlDef(c, false, 60);
        MetricValue cached = new MetricValue(500L, "LONG", "FETCHED");
        MetricCache cache = new MetricCache() {
            public MetricValue get(String key) { return cached; }
            public void put(String key, MetricValue value, int ttlSeconds) { }
        };
        EvalContextAssembler asm = new EvalContextAssembler(
                List.of(), Map.of("SQL_AGGREGATE", handler), resolver, cache, EXEC,1000L);

        EvalContext ctx = asm.assemble(event(Map.of()), List.of(snapWithDep("balance")), new EvalEnv(NOW, java.util.Map.of()));

        assertThat(ctx.getMetric("balance").value()).isEqualTo(500L);
        assertThat(calls.get()).isZero();
    }

    @Test
    void queryCarriesNow() {
        Instant[] seen = new Instant[1];
        MetricSourceHandler handler = q -> { seen[0] = q.now(); return new MetricValue(1L, "LONG", "FETCHED"); };
        MetricDefinitionResolver resolver = (t, c, v) -> sqlDef(c, false, 0);
        EvalContextAssembler asm = new EvalContextAssembler(
                List.of(), Map.of("SQL_AGGREGATE", handler), resolver, null, EXEC,1000L);

        asm.assemble(event(Map.of()), List.of(snapWithDep("balance")), new EvalEnv(NOW, java.util.Map.of()));

        assertThat(seen[0]).isEqualTo(NOW);
    }

    @Test
    void fetchTimeout_degradesToError() {
        // handler 睡 500ms 超过 50ms 超时 → invokeAll 中断该子任务 → 降级 error，且不挂满 500ms
        MetricSourceHandler slow = q -> {
            try { Thread.sleep(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return new MetricValue(1L, "LONG", "FETCHED");
        };
        MetricDefinitionResolver resolver = (t, c, v) -> sqlDef(c, false, 0);
        EvalContextAssembler asm = new EvalContextAssembler(
                List.of(), Map.of("SQL_AGGREGATE", slow), resolver, null, EXEC, 50L);

        EvalContext ctx = asm.assemble(event(Map.of()), List.of(snapWithDep("balance")), new EvalEnv(NOW, java.util.Map.of()));

        MetricValue mv = ctx.getMetric("balance");
        assertThat(mv.isError()).isTrue();
        assertThat(mv.errorCode()).isEqualTo("METRIC_FETCH_FAIL");
    }

    @Test
    void partialTimeout_fastSucceedsSlowDegrades() {
        // fast 瞬时、slow 睡 500ms；50ms 超时 → fast 得值、slow 降级，证明慢指标不拖挂整批、不毒化快指标
        MetricSourceHandler handler = q -> {
            if ("slow".equals(q.metricCode())) {
                try { Thread.sleep(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            return new MetricValue("slow".equals(q.metricCode()) ? 2L : 1L, "LONG", "FETCHED");
        };
        MetricDefinitionResolver resolver = (t, c, v) -> sqlDef(c, false, 0);
        EvalContextAssembler asm = new EvalContextAssembler(
                List.of(), Map.of("SQL_AGGREGATE", handler), resolver, null, EXEC, 50L);

        EvalContext ctx = asm.assemble(event(Map.of()),
                List.of(snapWithDep("fast"), snapWithDep("slow")), new EvalEnv(NOW, java.util.Map.of()));

        assertThat(ctx.getMetric("fast").value()).isEqualTo(1L);
        assertThat(ctx.getMetric("fast").isError()).isFalse();
        assertThat(ctx.getMetric("slow").isError()).isTrue();
    }
}
