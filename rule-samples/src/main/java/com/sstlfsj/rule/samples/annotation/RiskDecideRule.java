package com.sstlfsj.rule.samples.annotation;

import com.sstlfsj.rule.kernel.api.annotation.DecisionBinding;
import com.sstlfsj.rule.kernel.api.annotation.RuleDef;
import com.sstlfsj.rule.sdk.annotation.Decide;
import com.sstlfsj.rule.sdk.annotation.Fact;
import org.springframework.stereotype.Component;

/** 多分支风控示例:@Decide 在 Java 里直接算出决策码(替代决策树/表)。 */
@RuleDef(code = "risk-decide", sceneCode = "risk-demo", trigger = "txn", decisions = {
        @DecisionBinding(code = "BLOCK", priority = 90),
        @DecisionBinding(code = "REVIEW", priority = 50),
        @DecisionBinding(code = "ALLOW", priority = 10)})
@Component
public class RiskDecideRule {

    @Decide
    public String decide(@Fact("amount") Integer amount) {
        if (amount == null) return "ALLOW";
        if (amount >= 50000) return "BLOCK";
        if (amount >= 5000)  return "REVIEW";
        return "ALLOW";
    }
}
