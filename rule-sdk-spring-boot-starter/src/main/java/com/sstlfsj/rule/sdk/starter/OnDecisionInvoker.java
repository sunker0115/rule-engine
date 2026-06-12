package com.sstlfsj.rule.sdk.starter;

import com.sstlfsj.rule.sdk.DecisionFiredEvent;
import com.sstlfsj.rule.sdk.DecisionSink;
import com.sstlfsj.rule.sdk.FactResolver;
import com.sstlfsj.rule.sdk.annotation.OnDecision;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 写法乙:把 @OnDecision 方法按 decision code 建表,命中时 FactResolver 注入参数后反射调用;单处理器异常吞 + 续跑。 */
public final class OnDecisionInvoker implements DecisionSink {

    private static final Logger log = LoggerFactory.getLogger(OnDecisionInvoker.class);

    /** fromRuleCode 为空表示不限来源规则;非空则仅匹配该来源规则的决策。 */
    private record Handler(Object bean, Method method, String fromRuleCode) {}

    private final FactResolver factResolver;
    private final Map<String, List<Handler>> byCode = new HashMap<>();

    public OnDecisionInvoker(FactResolver factResolver, List<?> handlerBeans) {
        this.factResolver = factResolver;
        for (Object bean : handlerBeans) {
            for (Method m : bean.getClass().getMethods()) {
                OnDecision ann = m.getAnnotation(OnDecision.class);
                if (ann == null) continue;
                m.setAccessible(true);
                factResolver.validate(m.getParameters());
                for (String code : ann.value()) {
                    byCode.computeIfAbsent(code, k -> new ArrayList<>())
                            .add(new Handler(bean, m, ann.fromRuleCode()));
                }
            }
        }
    }

    /** @return 是否登记了订阅 code 的处理器(供 starter 启动期 warn 用)。 */
    public boolean hasHandlerFor(String code) { return byCode.containsKey(code); }

    /** @return 所有已登记订阅的 decision code(不可变视图),供启动期 orphan 核对。 */
    public java.util.Set<String> subscribedCodes() {
        return java.util.Collections.unmodifiableSet(byCode.keySet());
    }

    @Override
    public void accept(DecisionFiredEvent event) {
        List<Handler> handlers = byCode.get(event.decisionCode());
        if (handlers == null) return;
        for (Handler h : handlers) {
            // fromRuleCode 过滤:处理器指定了来源规则时,仅当决策出自该规则才触发
            if (!h.fromRuleCode().isEmpty() && !h.fromRuleCode().equals(event.fromRuleCode())) {
                continue;
            }
            try {
                Object[] args = factResolver.resolve(h.method().getParameters(), event.context(), event);
                h.method().invoke(h.bean(), args);
            } catch (Exception ex) {
                log.error("@OnDecision 处理器执行失败,已吞:decision={} handler={}#{}",
                        event.decisionCode(), h.bean().getClass().getName(), h.method().getName(), ex);
            }
        }
    }
}
