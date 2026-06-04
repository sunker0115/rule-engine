package com.sstlfsj.rule.sdk;

import com.sstlfsj.rule.kernel.api.model.EvalResult;
import com.sstlfsj.rule.kernel.api.model.RuleEvent;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RuleEngineClientTest {

    @Test
    void build_missingServerUrl_throwsIllegalArgument() {
        assertThatThrownBy(() -> RuleEngineClient.builder()
                .tenantId("t1")
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("serverUrl");
    }

    @Test
    void build_missingTenantId_throwsIllegalArgument() {
        assertThatThrownBy(() -> RuleEngineClient.builder()
                .serverUrl("http://localhost:8080")
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tenantId");
    }

    @Test
    void evaluate_emptyIndex_returnsMiss() {
        // SnapshotPoller 启动后连接不存在的端口，静默失败，index 保持空
        try (RuleEngineClient client = RuleEngineClient.builder()
                .serverUrl("http://localhost:19999")
                .tenantId("t1")
                .pollInterval(Duration.ofHours(1))
                .build()) {
            RuleEvent event = new RuleEvent("t1", "scene1", "ORDER", "sub1",
                    UUID.randomUUID().toString(), Instant.now(), Map.of(), Map.of());
            assertThat(client.evaluate(event).ruleHit()).isFalse();
        }
    }

    @Test
    void evaluate_callsEvalResultListener() {
        boolean[] called = {false};
        try (RuleEngineClient client = RuleEngineClient.builder()
                .serverUrl("http://localhost:19999")
                .tenantId("t1")
                .pollInterval(Duration.ofHours(1))
                .evalResultListener((ev, res) -> called[0] = true)
                .build()) {
            RuleEvent event = new RuleEvent("t1", "scene1", "ORDER", "sub1",
                    UUID.randomUUID().toString(), Instant.now(), Map.of(), Map.of());
            client.evaluate(event);
        }
        assertThat(called[0]).isTrue();
    }
}
