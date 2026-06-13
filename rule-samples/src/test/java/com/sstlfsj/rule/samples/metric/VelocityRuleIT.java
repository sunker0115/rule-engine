package com.sstlfsj.rule.samples.metric;

import com.sstlfsj.rule.kernel.api.model.EvalResult;
import com.sstlfsj.rule.kernel.api.model.EventSource;
import com.sstlfsj.rule.kernel.api.model.RuleEvent;
import com.sstlfsj.rule.sdk.RuleEngineClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Metric 注入端到端:{@code @Metric} 声明依赖 → 引擎评估前经 {@link VelocityMetrics} 预拉 →
 * 注入条件参数 → 驱动决策。frequent-user 近期交易数 5(≥3)命中 REVIEW;normal-user 为 1 不命中,
 * 证明决策确由预拉的 metric 驱动(而非事件 payload)。
 */
class VelocityRuleIT {

    @Test
    void fetchedMetric_drivesDecision() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        com.sstlfsj.rule.sdk.starter.RuleEngineClientAutoConfiguration.class))
                .withBean(VelocityRule.class)
                .withBean(VelocityMetrics.class)
                .run(ctx -> {
                    RuleEngineClient client = ctx.getBean(RuleEngineClient.class);

                    // 大额 + 近期交易数 5(≥3) → 命中
                    EvalResult frequent = client.evaluate(txn("frequent-user", 2000));
                    assertThat(frequent.ruleHit()).isTrue();

                    // 同样大额,但近期交易数 1(<3) → 不命中:metric 在驱动结果
                    EvalResult normal = client.evaluate(txn("normal-user", 2000));
                    assertThat(normal.ruleHit()).isFalse();
                });
    }

    private static RuleEvent txn(String subjectId, int amount) {
        return RuleEvent.builder().tenantId("").sceneCode("velocity-demo").eventType("txn")
                .subjectId(subjectId).eventId(UUID.randomUUID().toString())
                .occurredAt(Instant.now()).payload(Map.of("amount", amount))
                .source(EventSource.SDK).build();
    }
}
