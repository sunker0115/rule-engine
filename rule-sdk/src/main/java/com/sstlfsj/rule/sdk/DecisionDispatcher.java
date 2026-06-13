package com.sstlfsj.rule.sdk;

import com.sstlfsj.rule.kernel.api.model.Decision;
import com.sstlfsj.rule.kernel.api.model.EvalContext;
import com.sstlfsj.rule.kernel.api.model.EvalResult;
import com.sstlfsj.rule.kernel.api.model.RuleEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/** 按 EvalResult.hitDecisions 顺序,为每个命中决策向所有 sink 派发一个 DecisionFiredEvent;sink 异常吞 + 记日志续跑。 */
public final class DecisionDispatcher implements DecisionContextListener {

    private static final Logger log = LoggerFactory.getLogger(DecisionDispatcher.class);

    private final List<DecisionSink> sinks;

    public DecisionDispatcher(List<DecisionSink> sinks) {
        this.sinks = List.copyOf(sinks);
    }

    @Override
    public void onEvaluated(RuleEvent event, EvalResult result, EvalContext context) {
        if (result == null || result.hitDecisions().isEmpty()) return;
        for (Decision d : result.hitDecisions()) {
            DecisionFiredEvent fired = new DecisionFiredEvent(d.code(), d.priority(), d.category(),
                    d.fromRuleCode(), d.fromRuleVersion(), event, context);
            for (DecisionSink sink : sinks) {
                try {
                    sink.accept(fired);
                } catch (RuntimeException ex) {
                    log.error("决策 sink 处理失败,已吞:decision={} sink={}",
                            d.code(), sink.getClass().getName(), ex);
                }
            }
        }
    }
}
