package com.sstlfsj.rule.samples.service;

import com.sstlfsj.rule.kernel.api.model.EvalResult;
import com.sstlfsj.rule.kernel.api.model.EventSource;
import com.sstlfsj.rule.kernel.api.model.RuleEvent;
import com.sstlfsj.rule.sdk.RuleEngineClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 业务方法调用规则引擎的参考写法(拉模式):{@code @Service} 注入 starter 自动装配的
 * {@link RuleEngineClient},把业务对象转成 {@link RuleEvent} → {@code evaluate} → 按决策码走业务分支。
 * <p>业务代码<b>不直接依赖任何规则类</b>,只按 {@code (tenantId, sceneCode, eventType)} 三元组发事件;
 * 规则增删改不影响这里。与"推模式"(规则上的 {@code @OnDecision}/{@code @EventListener} 自动跑副作用)互补,
 * 二者可并存。
 */
@Service
@RequiredArgsConstructor
public class OrderService {

    /** 演示态用默认租户(空 = 继承 client 配置的租户);真实应用应从上下文/配置取。 */
    private static final String TENANT = "";

    private final RuleEngineClient ruleEngine;

    /**
     * 提交订单前过一遍风控规则,按决策返回处理结果。
     *
     * @param orderId 订单号(作 subjectId)
     * @param amount  订单金额(作 payload.amount,驱动风控 @Decide)
     * @return 订单处理结果
     */
    public OrderOutcome submit(String orderId, int amount) {
        RuleEvent event = RuleEvent.builder()
                .tenantId(TENANT)
                .sceneCode("risk-demo").eventType("txn")
                .subjectId(orderId).eventId(UUID.randomUUID().toString())
                .occurredAt(Instant.now())
                .payload(Map.of("amount", amount))
                .source(EventSource.SDK).build();

        EvalResult result = ruleEngine.evaluate(event);
        if (!result.ruleHit()) {
            return OrderOutcome.ACCEPTED;   // 无规则命中 = 放行
        }
        return switch (result.finalDecision().code()) {
            case "BLOCK"  -> OrderOutcome.REJECTED;
            case "REVIEW" -> OrderOutcome.PENDING_REVIEW;
            default       -> OrderOutcome.ACCEPTED;   // ALLOW
        };
    }

    /** 订单风控处理结果。 */
    public enum OrderOutcome {ACCEPTED, PENDING_REVIEW, REJECTED}
}
