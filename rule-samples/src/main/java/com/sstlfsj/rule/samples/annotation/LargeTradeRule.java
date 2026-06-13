package com.sstlfsj.rule.samples.annotation;

import com.sstlfsj.rule.kernel.api.annotation.DecisionBinding;
import com.sstlfsj.rule.kernel.api.annotation.RuleDef;
import com.sstlfsj.rule.sdk.DecisionFiredEvent;
import com.sstlfsj.rule.sdk.annotation.Condition;
import com.sstlfsj.rule.sdk.annotation.Fact;
import com.sstlfsj.rule.sdk.annotation.OnDecision;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 注解规则即代码(Easy Rules 风格):条件与动作写在同一个类里。
 * <ul>
 *   <li>{@code @RuleDef} + {@code @Condition}:大额交易且营业时段 → REVIEW 决策(引擎职责,只出决策);</li>
 *   <li>{@code @EventListener} / {@code @OnDecision}:命中 REVIEW 后的动作(消费方职责,进程内执行)。</li>
 * </ul>
 * "引擎只出决策、动作解耦"(D60)的边界仍在 —— 只是规则对"自己命中后做什么"的两类方法同址声明。
 * 若某动作要响应所有规则的 REVIEW(跨规则),则应放到独立处理器类、不要并到具体规则里。
 */
@RuleDef(
        code = "large-trade",
        sceneCode = "merchant-trade",
        eventTypes = "trade",
        decisions = @DecisionBinding(code = "REVIEW", priority = 50))
@Slf4j
@Component
public class LargeTradeRule {

    private final AtomicInteger eventCount = new AtomicInteger();
    private final AtomicInteger onDecisionCount = new AtomicInteger();

    /** 条件:大额(amount>5000)且落在营业时段 [9,18) 时命中复核;营业时段判断直接写在 Java 里。 */
    @Condition
    public boolean needsReview(@Fact("amount") Integer amount, @Fact("hour") Integer hour) {
        return amount != null && amount > 5000
                && hour != null && hour >= 9 && hour < 18;
    }

    /** 动作甲:Spring {@code @EventListener},命令式读 {@code e.fromRuleCode()} 分流。 */
    @EventListener
    public void onDecisionFired(DecisionFiredEvent e) {
        if (e.decision("REVIEW")) {
            eventCount.incrementAndGet();
            log.info("[annotation][甲 @EventListener] REVIEW 命中,来自规则={} priority={}",
                    e.fromRuleCode(), e.priority());
        }
    }

    /** 动作乙:{@code @OnDecision} 声明式只接本规则产出的 REVIEW,{@code @Fact} 注入 amount。 */
    @OnDecision(value = "REVIEW", fromRuleCode = "large-trade")
    public void onReview(@Fact("amount") Integer amount) {
        onDecisionCount.incrementAndGet();
        log.info("[annotation][乙 @OnDecision(fromRuleCode=large-trade)] 复核大额交易 amount={}", amount);
    }

    /** 甲路径累计触发次数(供集成测试断言)。 */
    public int eventCount() {
        return eventCount.get();
    }

    /** 乙路径累计触发次数(供集成测试断言)。 */
    public int onDecisionCount() {
        return onDecisionCount.get();
    }
}
