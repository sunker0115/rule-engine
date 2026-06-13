package com.sstlfsj.rule.samples.metric.featurestore;

import com.sstlfsj.rule.kernel.api.model.*;
import com.sstlfsj.rule.sdk.RuleEngineClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 一个接口式 handler 服务多个 metric:FeatureStoreHandler 按 metricCode 取不同特征(account_age_days /
 * device_risk_score),取数代码一样、差别在 key。AccountRiskRule 同时用两个 metric。
 */
class FeatureStoreIT {

    @Test
    void oneHandler_servesMultipleMetrics() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        com.sstlfsj.rule.sdk.starter.RuleEngineClientAutoConfiguration.class))
                .withBean(AccountRiskRule.class)
                .withBean(FeatureStoreHandler.class)
                .withUserConfiguration(FeatureStoreConfig.class)
                .run(ctx -> {
                    RuleEngineClient client = ctx.getBean(RuleEngineClient.class);
                    // new-user:账龄 3 天 + 设备风险 80 → 命中 REVIEW(两个 metric 都来自同一 handler)
                    assertThat(client.evaluate(signup("new-user")).ruleHit()).isTrue();
                    // vip-user:账龄 1200 + 设备风险 10 → 不命中
                    assertThat(client.evaluate(signup("vip-user")).ruleHit()).isFalse();
                });
    }

    private static RuleEvent signup(String subject) {
        return RuleEvent.builder().tenantId("").sceneCode("onboarding").eventType("signup")
                .subjectId(subject).eventId(UUID.randomUUID().toString()).occurredAt(Instant.now())
                .payload(Map.of()).source(EventSource.SDK).build();
    }
}
