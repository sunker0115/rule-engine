package com.sstlfsj.rule.samples.annotation;

import com.sstlfsj.rule.kernel.api.annotation.DecisionBinding;
import com.sstlfsj.rule.kernel.api.annotation.RuleDef;
import com.sstlfsj.rule.sdk.annotation.Fact;
import com.sstlfsj.rule.sdk.annotation.Score;
import com.sstlfsj.rule.sdk.annotation.ScoreBand;
import org.springframework.stereotype.Component;

/** 评分规则示例:信用分分档 → 拒/人工/过。@Score 返回分,@ScoreBand 映射决策。 */
@RuleDef(code = "credit-score", sceneCode = "credit-demo", trigger = "apply", decisions = {
        @DecisionBinding(code = "AUTO_REJECT", priority = 90),
        @DecisionBinding(code = "MANUAL_REVIEW", priority = 50),
        @DecisionBinding(code = "AUTO_PASS", priority = 10)})
@Component
public class CreditScoreRule {

    @Score
    @ScoreBand(min = 0,  decision = "AUTO_REJECT")
    @ScoreBand(min = 60, decision = "MANUAL_REVIEW")
    @ScoreBand(min = 80, decision = "AUTO_PASS")
    public double creditScore(@Fact("score") Integer score) {
        return score == null ? 0 : score;
    }
}
