package com.sstlfsj.rule.samples.annotation;

import com.sstlfsj.rule.kernel.api.annotation.DecisionBinding;
import com.sstlfsj.rule.kernel.api.annotation.RuleDef;
import com.sstlfsj.rule.sdk.annotation.Condition;
import com.sstlfsj.rule.sdk.annotation.Fact;
import org.springframework.stereotype.Component;

/**
 * 注解规则即代码(Easy Rules 风格):大额交易且在营业时段 → REVIEW。
 * <p>{@code @RuleDef} 声明规则元数据(编码/场景/触发事件/决策绑定),{@code @Condition} 标在
 * 唯一布尔方法上即规则条件,参数用 {@code @Fact} 从事件 payload 注入。营业时段判断直接写在
 * Java 里,无需再实现自定义 {@code @ConditionType} 算子 —— 这正是嵌入式 SDK 下 Easy Rules 风格
 * 相比"DSL + 自定义算子"的简化。
 */
@RuleDef(
        code = "large-trade",
        sceneCode = "merchant-trade",
        trigger = "trade",
        decisions = @DecisionBinding(code = "REVIEW", priority = 50))
@Component
public class LargeTradeRule {

    /** 大额(amount>5000)且落在营业时段 [9,18) 时命中复核。 */
    @Condition
    public boolean needsReview(@Fact("amount") Integer amount, @Fact("hour") Integer hour) {
        return amount != null && amount > 5000
                && hour != null && hour >= 9 && hour < 18;
    }
}
