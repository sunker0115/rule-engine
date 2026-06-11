package com.sstlfsj.rule.samples.easyrules;

import com.sstlfsj.rule.sdk.DecisionFiredEvent;
import com.sstlfsj.rule.sdk.annotation.Fact;
import com.sstlfsj.rule.sdk.annotation.OnDecision;
import org.springframework.context.event.EventListener;

import java.util.concurrent.atomic.AtomicInteger;

/** 两种动作写法并存:甲 @EventListener 监听 DecisionFiredEvent;乙 @OnDecision 注入 @Fact。 */
public class ReviewHandlers {

    private final AtomicInteger eventCount = new AtomicInteger();
    private final AtomicInteger onDecisionSum = new AtomicInteger();

    /** 甲:标准 Spring 事件监听。 */
    @EventListener
    public void onDecisionFired(DecisionFiredEvent e) {
        if (e.decision("EVEN")) eventCount.incrementAndGet();
    }

    /** 乙:按 decision code 订阅 + @Fact 注入。 */
    @OnDecision("EVEN")
    public void recordEven(@Fact("number") Integer number) {
        onDecisionSum.addAndGet(number);
    }

    public int eventCount()    { return eventCount.get(); }
    public int onDecisionSum() { return onDecisionSum.get(); }
}
