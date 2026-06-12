package com.sstlfsj.rule.samples.annotation;

import com.sstlfsj.rule.kernel.api.annotation.DecisionBinding;
import com.sstlfsj.rule.kernel.api.annotation.RuleDef;
import com.sstlfsj.rule.sdk.annotation.Fact;
import com.sstlfsj.rule.sdk.annotation.OnDecision;
import com.sstlfsj.rule.sdk.annotation.Score;
import com.sstlfsj.rule.sdk.annotation.ScoreBand;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 评分规则示例:信用分分档 → 拒/人工/过。@Score 返回分,@ScoreBand 映射决策。
 * <p>同址演示"推模式":不同评分档位产出不同决策码,用 {@code @OnDecision} 给每档挂各自动作
 * (AUTO_REJECT 拒件 / MANUAL_REVIEW 转人工;AUTO_PASS 直接过、无动作)。
 */
@RuleDef(code = "credit-score", sceneCode = "credit-demo", eventTypes = "apply", decisions = {
        @DecisionBinding(code = "AUTO_REJECT", priority = 90),
        @DecisionBinding(code = "MANUAL_REVIEW", priority = 50),
        @DecisionBinding(code = "AUTO_PASS", priority = 10)})
@Slf4j
@Component
public class CreditScoreRule {

    @Score
    @ScoreBand(min = 0,  decision = "AUTO_REJECT")
    @ScoreBand(min = 60, decision = "MANUAL_REVIEW")
    @ScoreBand(min = 80, decision = "AUTO_PASS")
    public double creditScore(@Fact("score") Integer score) {
        return score == null ? 0 : score;
    }

    /** 动作:命中 AUTO_REJECT → 自动拒件。 */
    @OnDecision(value = "AUTO_REJECT", fromRuleCode = "credit-score")
    public void onReject(@Fact("score") Integer score) {
        log.info("[credit] AUTO_REJECT score={} → 自动拒件", score);
    }

    /** 动作:命中 MANUAL_REVIEW → 转人工复核。 */
    @OnDecision(value = "MANUAL_REVIEW", fromRuleCode = "credit-score")
    public void onManualReview(@Fact("score") Integer score) {
        log.info("[credit] MANUAL_REVIEW score={} → 转人工复核", score);
    }
}
