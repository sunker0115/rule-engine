package com.sstlfsj.rule.samples.annotation;

import com.sstlfsj.rule.kernel.api.annotation.ConditionType;
import com.sstlfsj.rule.kernel.api.model.EvalContext;
import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;
import com.sstlfsj.rule.kernel.api.spi.condition.ConditionEvaluator;
import org.springframework.stereotype.Component;

/**
 * 自定义算子示例:内置算子不够用时,实现 ConditionEvaluator + 标 {@code @ConditionType},
 * starter 自动注册成新算子类型 BUSINESS_HOURS。
 * <p>本算子直接读事件 payload.hour 判断是否落在营业时段 [9,18)(demo 用确定值便于复现)。
 */
@ConditionType("BUSINESS_HOURS")
@Component
public class BusinessHoursEvaluator implements ConditionEvaluator {

    @Override
    public boolean evaluate(ConditionNode node, EvalContext ctx) {
        Object raw = ctx.event().payload().get("hour");
        int hour = raw instanceof Number n ? n.intValue() : -1;
        return hour >= 9 && hour < 18;
    }
}
