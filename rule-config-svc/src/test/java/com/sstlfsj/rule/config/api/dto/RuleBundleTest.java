package com.sstlfsj.rule.config.api.dto;

import com.sstlfsj.rule.kernel.api.model.AstBody;
import com.sstlfsj.rule.kernel.api.model.MetricDependency;
import com.sstlfsj.rule.kernel.api.model.PayloadDependency;
import com.sstlfsj.rule.kernel.api.model.ScriptBody;
import com.sstlfsj.rule.kernel.api.model.ScriptSource;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot.DecisionBinding;
import com.sstlfsj.rule.kernel.api.model.ast.AndNode;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** RuleBundle v2 / RuleImportResult 序列化往返测试。 */
class RuleBundleTest {

    private final ObjectMapper mapper = JsonMapper.builder().build();

    @Test
    void ruleBundle_v2_roundTrip_preservesMultiRuleStructure() {
        RuleBundle bundle = new RuleBundle(
                2, "sha256-abc", "2026-06-06T10:00:00Z", "loadtest",
                List.of(
                        new RuleBundle.RuleEntry(
                                "rule.night.transfer", "夜间大额转账", "AST_BOOLEAN", "risk.transfer",
                                new AstBody(new AndNode(List.of(), null, null)),
                                List.of(new DecisionBinding("BLOCK", 100)),
                                List.of(), List.of("transfer"),
                                List.of(new MetricDependency("account.age", 1)),
                                List.of(new PayloadDependency("amount", "NUMBER", true)),
                                "hash-abc"),
                        new RuleBundle.RuleEntry(
                                "rule.new.account", "新户拦截", "AST_BOOLEAN", "risk.transfer",
                                new AstBody(new AndNode(List.of(), null, null)),
                                List.of(), List.of(), List.of(), List.of(), List.of(),
                                null)),
                List.of(new RuleBundle.SceneSnapshot(
                        "risk.transfer", "转账风控", "desc", "USER", "PUSH", "HIGHEST_PRIORITY",
                        List.of("transfer"),
                        List.of(new PayloadFieldSpec("amount", "NUMBER", true, null, null, null, null, null)),
                        Map.of())),
                List.of(new RuleBundle.MetricEntry(
                        "account.age", 1, "账户年龄", "ATTRIBUTE", "LONG", Map.of(), 3600, true)),
                List.of(new RuleBundle.DecisionEntry(
                        "BLOCK", "拦截", 100, "拦截交易")));

        String json = mapper.writeValueAsString(bundle);
        RuleBundle back = mapper.readValue(json, RuleBundle.class);

        assertThat(back).isEqualTo(bundle);
        assertThat(back.formatVersion()).isEqualTo(2);
        assertThat(back.revision()).isEqualTo("sha256-abc");
        assertThat(back.sourceTenant()).isEqualTo("loadtest");
        assertThat(back.rules()).hasSize(2);
        assertThat(back.rules().getFirst().body()).isInstanceOf(AstBody.class);
        assertThat(((AstBody) back.rules().getFirst().body()).conditionAst()).isInstanceOf(AndNode.class);
        assertThat(back.rules().getFirst().decisionBindings()).containsExactly(new DecisionBinding("BLOCK", 100));
        assertThat(back.rules().getFirst().metricDependencies()).containsExactly(new MetricDependency("account.age", 1));
        assertThat(back.rules().getFirst().payloadDependencies()).containsExactly(new PayloadDependency("amount", "NUMBER", true));
        assertThat(back.rules().getFirst().contentHash()).isEqualTo("hash-abc");
        assertThat(back.scenes()).hasSize(1);
        assertThat(back.metricDefinitions().getFirst().sourceType()).isEqualTo("ATTRIBUTE");
        assertThat(back.decisionDefinitions().getFirst().code()).isEqualTo("BLOCK");
    }

    @Test
    void ruleBundle_v2_scriptEntry_roundTrip() {
        // EXPRESSION_SCRIPT 规则 body(ScriptBody) 随 bundle 携带，不丢失
        ScriptSource script = new ScriptSource("metrics.amount > 1000", "CEL");
        RuleBundle bundle = new RuleBundle(
                2, "rev", "2026-06-06T10:00:00Z", "t1",
                List.of(new RuleBundle.RuleEntry(
                        "rule.cel", "CEL规则", "EXPRESSION_SCRIPT", "scene1",
                        new ScriptBody(script), List.of(), List.of(), List.of(), List.of(), List.of(),
                        "hash-cel")),
                List.of(), List.of(), List.of());

        String json = mapper.writeValueAsString(bundle);
        RuleBundle back = mapper.readValue(json, RuleBundle.class);

        assertThat(back.rules().getFirst().body()).isInstanceOf(ScriptBody.class);
        ScriptBody sb = (ScriptBody) back.rules().getFirst().body();
        assertThat(sb.script().source()).isEqualTo("metrics.amount > 1000");
        assertThat(sb.script().lang()).isEqualTo("CEL");
    }

    @Test
    void ruleImportResult_roundTrip() {
        RuleImportResult result = new RuleImportResult(
                List.of(new RuleImportResult.ImportedRule(10L, 20L, 1L, "rule.a", "risk.transfer", false)),
                List.of("risk.transfer"), List.of(),
                List.of("account.age"), List.of(), List.of("balance.sql"),
                List.of("BLOCK"), List.of());

        String json = mapper.writeValueAsString(result);
        RuleImportResult back = mapper.readValue(json, RuleImportResult.class);

        assertThat(back).isEqualTo(result);
        assertThat(back.rules().getFirst().code()).isEqualTo("rule.a");
        assertThat(back.metricsRequiringReview()).containsExactly("balance.sql");
        assertThat(back.scenesCreated()).containsExactly("risk.transfer");
    }
}
