package com.sstlfsj.rule.samples.annotation;

import com.sstlfsj.rule.kernel.api.annotation.DecisionBinding;
import com.sstlfsj.rule.kernel.api.annotation.RuleDef;
import com.sstlfsj.rule.sdk.Condition;
import com.sstlfsj.rule.sdk.InlineRuleSpec;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 注解规则即代码:大额交易且在营业时段 → REVIEW。
 * <p>{@code @RuleDef} 声明规则元数据(版本/租户/场景/触发/决策绑定),
 * {@code condition()} 用 Condition DSL 链式写条件;starter 自动扫描装载。
 */
@RuleDef(
        id = 9001L,
        tenantId = "9001",
        sceneCode = "merchant-trade",
        trigger = "trade",
        decisions = @DecisionBinding(code = "REVIEW", priority = 50))
@Component
public class LargeTradeRule implements InlineRuleSpec {

    @Override
    public Condition condition() {
        // 内置算子 payloadGt + 自定义算子 BUSINESS_HOURS 组合
        return Condition.payloadGt("amount", 5000)
                .and(Condition.of("BUSINESS_HOURS", "hour", Map.of()));
    }
}
