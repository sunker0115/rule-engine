package com.sstlfsj.rule.config.api.dto;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** RuleImportResult Jackson 序列化往返测试。完整场景见 {@link RuleBundleTest#ruleImportResult_roundTrip}。 */
class RuleImportResultTest {

    private final ObjectMapper mapper = JsonMapper.builder().build();

    @Test
    void importedRule_roundTrip() {
        RuleImportResult.ImportedRule rule = new RuleImportResult.ImportedRule(
                10L, 20L, 1L, "rule.a", "risk.transfer", false);

        RuleImportResult result = new RuleImportResult(
                List.of(rule),
                List.of("risk.transfer"),
                List.of(),
                List.of("account.age"),
                List.of(),
                List.of("balance.sql"),
                List.of("BLOCK"),
                List.of());

        String json = mapper.writeValueAsString(result);
        RuleImportResult back = mapper.readValue(json, RuleImportResult.class);

        assertThat(back).isEqualTo(result);
        assertThat(back.rules().getFirst().ruleDefinitionId()).isEqualTo(10L);
        assertThat(back.rules().getFirst().ruleVersionId()).isEqualTo(20L);
        assertThat(back.rules().getFirst().version()).isEqualTo(1L);
        assertThat(back.rules().getFirst().code()).isEqualTo("rule.a");
        assertThat(back.rules().getFirst().sceneCode()).isEqualTo("risk.transfer");
        assertThat(back.rules().getFirst().ruleAlreadyExisted()).isFalse();
        assertThat(back.scenesCreated()).containsExactly("risk.transfer");
        assertThat(back.metricsRequiringReview()).containsExactly("balance.sql");
        assertThat(back.decisionsCreated()).containsExactly("BLOCK");
    }
}
