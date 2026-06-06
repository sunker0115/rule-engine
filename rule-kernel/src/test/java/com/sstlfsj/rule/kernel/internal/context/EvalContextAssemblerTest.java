package com.sstlfsj.rule.kernel.internal.context;

import com.sstlfsj.rule.kernel.api.model.*;
import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;
import com.sstlfsj.rule.kernel.api.spi.metric.MetricDefinitionResolver;
import com.sstlfsj.rule.kernel.api.spi.subject.SubjectLoader;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EvalContextAssemblerTest {

    private static final Instant NOW = Instant.parse("2026-06-01T00:00:00Z");

    private RuleEvent event(Map<String, Object> providedMetrics) {
        return new RuleEvent("e1", "t1", "s1", "sub1", "EVT",
                Instant.now(), Map.of(), providedMetrics);
    }

    @Test
    void noSubjectLoader_buildsMinimalSubject() {
        EvalContextAssembler assembler = new EvalContextAssembler(List.of(), List.of());
        EvalContext ctx = assembler.assemble(event(Map.of("score", 100)), List.of(), NOW);

        assertThat(ctx.subject().subjectId()).isEqualTo("sub1");
        assertThat(ctx.subject().subjectType()).isEqualTo(SubjectType.USER);
    }

    @Test
    void providedMetrics_populatedInContext() {
        EvalContextAssembler assembler = new EvalContextAssembler(List.of(), List.of());
        EvalContext ctx = assembler.assemble(event(Map.of("score", 42, "tag", "vip")), List.of(), NOW);

        assertThat(ctx.metrics()).containsKey("score");
        assertThat(ctx.metrics().get("score").value()).isEqualTo(42);
        assertThat(ctx.metrics().get("tag").value()).isEqualTo("vip");
    }

    @Test
    void withSubjectLoader_usesLoader() {
        SubjectLoader loader = new SubjectLoader() {
            @Override
            public List<SubjectType> supportedTypes() { return List.of(SubjectType.USER); }
            @Override
            public Subject load(String id, SubjectType type, RuleEvent event) {
                return new Subject(id, type, Map.of("level", "gold"));
            }
        };

        EvalContextAssembler assembler = new EvalContextAssembler(List.of(loader), List.of());
        EvalContext ctx = assembler.assemble(event(Map.of()), List.of(), NOW);

        assertThat(ctx.subject().attributes()).containsEntry("level", "gold");
    }

    @Test
    void subjectLoaderThrows_fallsBackToEmpty() {
        SubjectLoader failingLoader = new SubjectLoader() {
            @Override
            public List<SubjectType> supportedTypes() { return List.of(SubjectType.USER); }
            @Override
            public Subject load(String id, SubjectType type, RuleEvent event) {
                throw new RuntimeException("loader error");
            }
        };

        EvalContextAssembler assembler = new EvalContextAssembler(List.of(failingLoader), List.of());
        EvalContext ctx = assembler.assemble(event(Map.of()), List.of(), NOW);

        assertThat(ctx.subject().subjectId()).isEqualTo("sub1");
        assertThat(ctx.subject().attributes()).isEmpty();
    }

    @Test
    void emptyProvidedMetrics_contextMetricsEmpty() {
        EvalContextAssembler assembler = new EvalContextAssembler(List.of(), List.of());
        EvalContext ctx = assembler.assemble(event(Map.of()), List.of(), NOW);

        assertThat(ctx.metrics()).isEmpty();
    }

    @Test
    void now_isPropagatedToContext() {
        EvalContextAssembler assembler = new EvalContextAssembler(List.of(), List.of());
        EvalContext ctx = assembler.assemble(event(Map.of()), List.of(), NOW);

        assertThat(ctx.now()).isEqualTo(NOW);
    }

    @Test
    void metricDependency_extractsCodeFromObject() {
        // 无 resolver 时退化为历史行为：provided 指标直接进 context（不走版本解析）
        ConditionNode ast = new ConditionNode("GT", "balance", null, Map.of("threshold", 0), 0.0);
        RuleVersionSnapshot snap = RuleVersionSnapshot.builder()
                .ruleVersionId(1L).sceneCode("s1").tenantId("t1").conditionAst(ast)
                .addMetricDependency("balance", 1).build();

        EvalContextAssembler assembler = new EvalContextAssembler(List.of(), List.of());
        EvalContext ctx = assembler.assemble(event(Map.of("balance", 500)), List.of(snap), NOW);

        assertThat(ctx.metrics()).containsKey("balance");
        assertThat(ctx.metrics().get("balance").value()).isEqualTo(500);
    }

    @Test
    void allowProvided_false_ignoresProvidedValue_andFallsToError() {
        // allowProvided=false：provided 传值被忽略（触发 log.warn，原 System.err.println 已改为 slf4j），
        // 走 fetch 管线；无 handler 时结果为 METRIC_FETCH_FAIL。覆盖 EvalContextAssembler 122-126 行。
        MetricDescriptor def = new MetricDescriptor("kyc", 1, "SQL", "INT", false, 0, Map.of());
        MetricDefinitionResolver resolver = (tenantId, code, version) ->
                "kyc".equals(code) ? def : null;

        ConditionNode ast = new ConditionNode("GT", "kyc", null, Map.of(), 0.0);
        RuleVersionSnapshot snap = RuleVersionSnapshot.builder()
                .ruleVersionId(2L).sceneCode("s1").tenantId("t1").conditionAst(ast)
                .addMetricDependency("kyc", 1).build();

        EvalContextAssembler assembler = new EvalContextAssembler(
                List.of(), Map.of(), resolver, null, null, 0L);
        EvalContext ctx = assembler.assemble(event(Map.of("kyc", 99)), List.of(snap), NOW);

        // provided 值 99 被忽略，fetch 无 handler → METRIC_FETCH_FAIL（isError=true）
        assertThat(ctx.metrics()).containsKey("kyc");
        assertThat(ctx.metrics().get("kyc").isError()).isTrue();
    }
}
