package com.sstlfsj.rule.samples.annotation;

import com.sstlfsj.rule.kernel.api.annotation.DecisionBinding;
import com.sstlfsj.rule.kernel.api.annotation.RuleDef;
import com.sstlfsj.rule.sdk.annotation.Condition;
import com.sstlfsj.rule.sdk.annotation.Fact;
import org.springframework.stereotype.Component;

/**
 * 嵌套 payload 路径示例:事件 payload 为 {"order":{"amount":N,"channel":"X"}} 结构时,
 * 用 @Fact("order.amount") 直接下钻取嵌套字段,无需在调用方先摊平。
 */
@RuleDef(code = "nested-order", sceneCode = "order-demo", trigger = "order",
        decisions = @DecisionBinding(code = "REVIEW", priority = 10))
@Component
public class NestedOrderRule {

    /** 嵌套大额订单(order.amount > 10000)→ 复核。 */
    @Condition
    public boolean bigNestedOrder(@Fact("order.amount") Integer amount) {
        return amount != null && amount > 10000;
    }
}
