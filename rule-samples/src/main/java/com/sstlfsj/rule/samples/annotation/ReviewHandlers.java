package com.sstlfsj.rule.samples.annotation;

import com.sstlfsj.rule.sdk.DecisionFiredEvent;
import com.sstlfsj.rule.sdk.annotation.Fact;
import com.sstlfsj.rule.sdk.annotation.OnDecision;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 决策命中后的动作处理器,演示两种解耦写法并存。引擎只出决策,动作在消费方进程内执行(D60)。
 * <p>甲:标准 Spring {@code @EventListener} 监听 {@link DecisionFiredEvent},自行按决策码分流,
 *     可直接读 {@code e.fromRuleCode()} 拿到产出该决策的规则;
 * <p>乙:{@code @OnDecision} 按 decision code 订阅,并用 {@code fromRuleCode} 声明式过滤到指定规则,
 *     参数用 {@code @Fact} 从命中事件注入。
 */
@Component
public class ReviewHandlers {

    private final AtomicInteger eventCount = new AtomicInteger();
    private final AtomicInteger onDecisionCount = new AtomicInteger();

    /** 甲:监听全部决策事件,挑出 REVIEW,并打印产出它的规则来源。 */
    @EventListener
    public void onDecisionFired(DecisionFiredEvent e) {
        if (e.decision("REVIEW")) {
            eventCount.incrementAndGet();
            System.out.println("[annotation][甲 @EventListener] REVIEW 命中,来自规则=" + e.fromRuleCode()
                    + " priority=" + e.priority());
        }
    }

    /** 乙:只订阅 large-trade 规则产出的 REVIEW(fromRuleCode 声明式过滤),注入命中事件的 amount。 */
    @OnDecision(value = "REVIEW", fromRuleCode = "large-trade")
    public void onReview(@Fact("amount") Integer amount) {
        onDecisionCount.incrementAndGet();
        System.out.println("[annotation][乙 @OnDecision(fromRuleCode=large-trade)] 复核大额交易 amount=" + amount);
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
