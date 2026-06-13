package com.sstlfsj.rule.samples.metric.featurestore;

import com.sstlfsj.rule.kernel.api.annotation.DecisionBinding;
import com.sstlfsj.rule.kernel.api.annotation.RuleDef;
import com.sstlfsj.rule.sdk.annotation.Condition;
import com.sstlfsj.rule.sdk.annotation.Metric;
import org.springframework.stereotype.Component;

/** 同时消费两个 metric(都来自 FeatureStoreHandler):新账户 + 高风险设备 → 复核。 */
@RuleDef(code = "account-risk", sceneCode = "onboarding", eventTypes = "signup",
        decisions = @DecisionBinding(code = "REVIEW", priority = 50))
@Component
public class AccountRiskRule {

    @Condition
    public boolean risky(@Metric("account_age_days") Long ageDays,
                         @Metric("device_risk_score") Long deviceRisk) {
        return ageDays != null && ageDays < 30
                && deviceRisk != null && deviceRisk >= 50;
    }
}
