package com.sstlfsj.rule.sdk.source;

import com.sstlfsj.rule.kernel.api.annotation.DecisionBinding;
import com.sstlfsj.rule.kernel.api.annotation.RuleDef;
import com.sstlfsj.rule.kernel.api.model.EvalResult;
import com.sstlfsj.rule.kernel.api.model.RuleEvent;
import com.sstlfsj.rule.kernel.internal.index.SceneRuleIndex;
import com.sstlfsj.rule.sdk.Condition;
import com.sstlfsj.rule.sdk.InlineRuleSpec;
import com.sstlfsj.rule.sdk.RuleEngineClient;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AnnotationRuleSourceTest {

    @RuleDef(code = "amount-gt-1000", tenantId = "t1", sceneCode = "fraud",
             trigger = "TRANSACTION",
             decisions = @DecisionBinding(code = "BLOCK", priority = 100))
    static class AmountGt1000 implements InlineRuleSpec {
        @Override public Condition condition() { return Condition.gt("amount", 1000); }
    }

    @RuleDef(code = "always-pass", tenantId = "t1", sceneCode = "scene",
             decisions = @DecisionBinding(code = "PASS", priority = 10))
    static class AlwaysTrueNoTrigger implements InlineRuleSpec {
        @Override public Condition condition() { return Condition.always(); }
    }

    // 未标注 @RuleDef，应被跳过
    static class NoAnnotation implements InlineRuleSpec {
        @Override public Condition condition() { return Condition.always(); }
    }

    @RuleDef(code = "multi-decision", tenantId = "t1", sceneCode = "multi",
             trigger = "EV",
             decisions = {@DecisionBinding(code = "A", priority = 50),
                          @DecisionBinding(code = "B", priority = 10)})
    static class MultiDecision implements InlineRuleSpec {
        @Override public Condition condition() { return Condition.always(); }
    }

    // 省略 tenantId，装载时用 defaultTenantId 兜底
    @RuleDef(code = "default-tenant", sceneCode = "scene")
    static class DefaultTenantRule implements InlineRuleSpec {
        @Override public Condition condition() { return Condition.always(); }
    }

    @Test
    void loadInto_writesRuleToIndex() {
        SceneRuleIndex index = new SceneRuleIndex();
        new AnnotationRuleSource(List.of(new AmountGt1000())).loadInto(index);
        assertThat(index.match("t1", "fraud", "TRANSACTION")).isNotEmpty();
    }

    @Test
    void loadInto_snapshotCarriesCodeAndDefaultVersionAndStableId() {
        SceneRuleIndex index = new SceneRuleIndex();
        new AnnotationRuleSource(List.of(new AmountGt1000())).loadInto(index);
        var snapshots = index.match("t1", "fraud", "TRANSACTION");
        assertThat(snapshots).hasSize(1);
        var snap = snapshots.get(0);
        assertThat(snap.code()).isEqualTo("amount-gt-1000");
        assertThat(snap.version()).isEqualTo(1L);
        assertThat(snap.ruleVersionId()).isNotNull();
    }

    @Test
    void loadInto_idIsStableAcrossReloads() {
        SceneRuleIndex first = new SceneRuleIndex();
        SceneRuleIndex second = new SceneRuleIndex();
        new AnnotationRuleSource(List.of(new AmountGt1000())).loadInto(first);
        new AnnotationRuleSource(List.of(new AmountGt1000())).loadInto(second);
        assertThat(first.match("t1", "fraud", "TRANSACTION").get(0).ruleVersionId())
                .isEqualTo(second.match("t1", "fraud", "TRANSACTION").get(0).ruleVersionId());
    }

    @Test
    void loadInto_blankTenant_usesDefaultTenantId() {
        SceneRuleIndex index = new SceneRuleIndex();
        new AnnotationRuleSource(List.of(new DefaultTenantRule()), "fallback-tenant").loadInto(index);
        var snapshots = index.match("fallback-tenant", "scene", "ANY");
        assertThat(snapshots).hasSize(1);
        assertThat(snapshots.get(0).tenantId()).isEqualTo("fallback-tenant");
    }

    @Test
    void specWithoutAnnotation_isSkipped() {
        SceneRuleIndex index = new SceneRuleIndex();
        new AnnotationRuleSource(List.of(new NoAnnotation())).loadInto(index);
        // 无 @RuleDef，索引保持空
        assertThat(index.match("t1", "fraud", "TRANSACTION")).isEmpty();
    }

    @Test
    void emptyTrigger_usesWildcard() {
        SceneRuleIndex index = new SceneRuleIndex();
        new AnnotationRuleSource(List.of(new AlwaysTrueNoTrigger())).loadInto(index);
        // "*" 通配：任意 eventType 都能命中
        assertThat(index.match("t1", "scene", "ANY_EVENT")).isNotEmpty();
    }

    @Test
    void multipleSpecs_allLoaded() {
        SceneRuleIndex index = new SceneRuleIndex();
        new AnnotationRuleSource(List.of(new AmountGt1000(), new MultiDecision())).loadInto(index);
        assertThat(index.match("t1", "fraud", "TRANSACTION")).isNotEmpty();
        assertThat(index.match("t1", "multi", "EV")).isNotEmpty();
    }

    @Test
    void evaluate_viaRuleEngineClient_hit() {
        try (RuleEngineClient client = RuleEngineClient.builder()
                .ruleSource(new AnnotationRuleSource(List.of(new AmountGt1000())))
                .build()) {
            RuleEvent hit = new RuleEvent("t1", "fraud", "TRANSACTION",
                    "sub1", UUID.randomUUID().toString(),
                    Instant.now(), Map.of(), Map.of("amount", 1500), com.sstlfsj.rule.kernel.api.model.EventSource.SDK);
            assertThat(client.evaluate(hit).ruleHit()).isTrue();

            RuleEvent miss = new RuleEvent("t1", "fraud", "TRANSACTION",
                    "sub1", UUID.randomUUID().toString(),
                    Instant.now(), Map.of(), Map.of("amount", 500), com.sstlfsj.rule.kernel.api.model.EventSource.SDK);
            assertThat(client.evaluate(miss).ruleHit()).isFalse();
        }
    }
}
