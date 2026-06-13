package com.sstlfsj.rule.samples.annotation;

import com.sstlfsj.rule.kernel.api.annotation.DecisionBinding;
import com.sstlfsj.rule.kernel.api.annotation.RuleDef;
import com.sstlfsj.rule.sdk.annotation.Decide;
import com.sstlfsj.rule.sdk.annotation.Fact;
import com.sstlfsj.rule.sdk.annotation.OnDecision;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 多分支风控示例:@Decide 在 Java 里直接算出决策码(替代决策树/表)。
 * <p>同址演示"推模式":一条规则产出多个不同决策码,用 {@code @OnDecision} 给每个码挂各自的动作
 * (BLOCK 拦截 / REVIEW 转人工;ALLOW 放行无动作)。{@code fromRuleCode} 限定只接本规则的决策。
 */
@RuleDef(code = "risk-decide", sceneCode = "risk-demo", eventTypes = "txn", decisions = {
        @DecisionBinding(code = "BLOCK", priority = 90),
        @DecisionBinding(code = "REVIEW", priority = 50),
        @DecisionBinding(code = "ALLOW", priority = 10)})
@Slf4j
@Component
public class RiskDecideRule {

    @Decide
    public String decide(@Fact("amount") Integer amount) {
        if (amount == null) return "ALLOW";
        if (amount >= 50000) return "BLOCK";
        if (amount >= 5000)  return "REVIEW";
        return "ALLOW";
    }

    /** 动作:命中 BLOCK → 拦截交易。 */
    @OnDecision(value = "BLOCK", fromRuleCode = "risk-decide")
    public void onBlock(@Fact("amount") Integer amount) {
        log.info("[risk] BLOCK amount={} → 拦截交易", amount);
    }

    /** 动作:命中 REVIEW → 转人工复核。 */
    @OnDecision(value = "REVIEW", fromRuleCode = "risk-decide")
    public void onReview(@Fact("amount") Integer amount) {
        log.info("[risk] REVIEW amount={} → 转人工复核", amount);
    }
}
