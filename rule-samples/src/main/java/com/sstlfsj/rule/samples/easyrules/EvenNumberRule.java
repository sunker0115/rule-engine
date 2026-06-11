package com.sstlfsj.rule.samples.easyrules;

import com.sstlfsj.rule.kernel.api.annotation.DecisionBinding;
import com.sstlfsj.rule.kernel.api.annotation.RuleDef;
import com.sstlfsj.rule.sdk.annotation.Condition;
import com.sstlfsj.rule.sdk.annotation.Fact;

/** Easy Rules 风格样例:偶数 → EVEN 决策。条件即一个布尔方法,payload.number 经 @Fact 注入。 */
@RuleDef(code = "even-number", sceneCode = "number-demo", trigger = "number",
        decisions = @DecisionBinding(code = "EVEN", priority = 1))
public class EvenNumberRule {
    @Condition
    public boolean isEven(@Fact("number") Integer number) {
        return number % 2 == 0;
    }
}
