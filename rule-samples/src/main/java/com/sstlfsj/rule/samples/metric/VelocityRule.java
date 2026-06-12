package com.sstlfsj.rule.samples.metric;

import com.sstlfsj.rule.kernel.api.annotation.DecisionBinding;
import com.sstlfsj.rule.kernel.api.annotation.RuleDef;
import com.sstlfsj.rule.sdk.annotation.Condition;
import com.sstlfsj.rule.sdk.annotation.Fact;
import com.sstlfsj.rule.sdk.annotation.Metric;
import org.springframework.stereotype.Component;

/**
 * Metric 注入示例:大额交易且该主体近期交易频繁 → 复核。
 * <p>{@code @Metric("recent_txn_count")} 一身二职:① 声明本规则依赖该 metric(驱动评估前预拉);
 * ② 把取到的值注入条件参数。预拉由匹配的 {@code @MetricSourceType} handler({@link RecentTxnCountHandler})
 * 完成,metric 定义(走哪个 sourceType / ttl 等)由 {@link MetricDemoConfig} 提供。
 * <p>注意 {@code recentCount} 不来自事件 payload,而是引擎在评估前替你取好的派生指标。
 */
@RuleDef(code = "velocity", sceneCode = "velocity-demo", eventTypes = "txn",
        decisions = @DecisionBinding(code = "REVIEW", priority = 50))
@Component
public class VelocityRule {

    /** 大额(amount>1000)且近期交易数 ≥3 时命中复核。 */
    @Condition
    public boolean suspicious(@Fact("amount") Integer amount,
                              @Metric("recent_txn_count") Integer recentCount) {
        return amount != null && amount > 1000
                && recentCount != null && recentCount >= 3;
    }
}
