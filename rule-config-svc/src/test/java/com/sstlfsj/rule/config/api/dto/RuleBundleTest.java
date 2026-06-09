package com.sstlfsj.rule.config.api.dto;

import com.sstlfsj.rule.kernel.api.model.MetricDependency;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot.DecisionBinding;
import com.sstlfsj.rule.kernel.api.model.ast.AndNode;
import com.sstlfsj.rule.kernel.api.model.ast.AstNode;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** RuleBundle / RuleImportResult 序列化往返测试（无损保留 JSON 列原文，多规则结构）。 */
class RuleBundleTest {

    private final ObjectMapper mapper = JsonMapper.builder().build();

    @Test
    void ruleBundle_roundTrip_preservesMultiRuleStructure() {
        RuleBundle bundle = new RuleBundle(
                1, "2026-06-06T10:00:00Z", "1",
                List.of(
                        new RuleBundle.RuleEntry(
                                "rule.night.transfer", "夜间大额转账", "AST_BOOLEAN", "risk.transfer",
                                new AndNode(List.of(), null, null),
                                List.of(new DecisionBinding("BLOCK", 100)),
                                List.of(), List.of("transfer"),
                                List.of(new MetricDependency("account.age", 1))),
                        new RuleBundle.RuleEntry(
                                "rule.new.account", "新户拦截", "AST_BOOLEAN", "risk.transfer",
                                new AndNode(List.of(), null, null), List.of(), List.of(), List.of(), List.of())),
                List.of(new RuleBundle.SceneSnapshot(
                        "risk.transfer", "转账风控", "desc", "USER", "PUSH", "HIGHEST_PRIORITY",
                        List.of("transfer"),
                        List.of(new PayloadFieldSpec("amount", "NUMBER", true, null, null, null, null, null)),
                        Map.of(), 1)),
                List.of(new RuleBundle.MetricEntry(
                        "account.age", 1, "账户年龄", "ATTRIBUTE", "LONG", Map.of(), 3600, true)),
                List.of(new RuleBundle.DecisionEntry(
                        "BLOCK", "拦截", 100, "拦截交易",
                        "[{\"actionId\":\"a1\",\"actionType\":\"BLOCK_TRANSACTION\",\"sortOrder\":0,\"params\":{}}]")),
                List.of("BLOCK_TRANSACTION"));

        String json = mapper.writeValueAsString(bundle);
        RuleBundle back = mapper.readValue(json, RuleBundle.class);

        assertThat(back).isEqualTo(bundle);
        assertThat(back.rules()).hasSize(2);
        AstNode backAst = back.rules().getFirst().conditionAst();
        assertThat(backAst).isInstanceOf(AndNode.class);
        assertThat(back.rules().getFirst().decisionBindings()).containsExactly(new DecisionBinding("BLOCK", 100));
        assertThat(back.rules().getFirst().metricDependencies()).containsExactly(new MetricDependency("account.age", 1));
        assertThat(back.scenes()).hasSize(1);
        assertThat(back.metricDefinitions().getFirst().sourceType()).isEqualTo("ATTRIBUTE");
        assertThat(back.decisionDefinitions().getFirst().code()).isEqualTo("BLOCK");
        assertThat(back.actionTypeManifest()).containsExactly("BLOCK_TRANSACTION");
    }

    @Test
    void ruleImportResult_roundTrip() {
        RuleImportResult result = new RuleImportResult(
                List.of(new RuleImportResult.ImportedRule(10L, 20L, 1L, "rule.a", "risk.transfer", false)),
                List.of("risk.transfer"), List.of(),
                List.of("account.age"), List.of(), List.of("balance.sql"),
                List.of("BLOCK"), List.of(), List.of("BLOCK_TRANSACTION"));

        String json = mapper.writeValueAsString(result);
        RuleImportResult back = mapper.readValue(json, RuleImportResult.class);

        assertThat(back).isEqualTo(result);
        assertThat(back.rules().getFirst().code()).isEqualTo("rule.a");
        assertThat(back.metricsRequiringReview()).containsExactly("balance.sql");
        assertThat(back.scenesCreated()).containsExactly("risk.transfer");
    }
}
