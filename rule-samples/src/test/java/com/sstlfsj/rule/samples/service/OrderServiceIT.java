package com.sstlfsj.rule.samples.service;

import com.sstlfsj.rule.samples.annotation.RiskDecideRule;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 业务服务调用规则引擎端到端:starter 装配 {@link RiskDecideRule},{@link OrderService} 注入
 * RuleEngineClient,按 @Decide 返回的风控决策把订单映射成不同处理结果。
 */
class OrderServiceIT {

    @Test
    void submit_mapsRiskDecisionToOrderOutcome() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        com.sstlfsj.rule.sdk.starter.RuleEngineClientAutoConfiguration.class))
                .withBean(RiskDecideRule.class)
                .withBean(OrderService.class)
                .run(ctx -> {
                    OrderService orders = ctx.getBean(OrderService.class);

                    assertThat(orders.submit("o-1", 99999)).isEqualTo(OrderService.OrderOutcome.REJECTED);       // BLOCK
                    assertThat(orders.submit("o-2", 8000)).isEqualTo(OrderService.OrderOutcome.PENDING_REVIEW);  // REVIEW
                    assertThat(orders.submit("o-3", 100)).isEqualTo(OrderService.OrderOutcome.ACCEPTED);         // ALLOW
                });
    }
}
