package com.sstlfsj.rule.kernel.internal.condition;

import com.sstlfsj.rule.kernel.api.model.ConditionParams;
import com.sstlfsj.rule.kernel.api.model.EvalContext;
import com.sstlfsj.rule.kernel.api.model.SceneDefaultParams;
import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;
import com.sstlfsj.rule.kernel.api.spi.condition.ConditionEvaluator;
import com.sstlfsj.rule.kernel.internal.condition.time.PlaceholderResolver;
import com.sstlfsj.rule.kernel.internal.condition.time.TimeZoneResolver;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;

/**
 * time.occurred_at 内置条件：对 event.occurredAt() 做 BEFORE/AFTER/BETWEEN 区间比较（B20 §4）。
 * value/start/end 支持 ISO-8601 与 $now；$today 不适用（时间点语义）→ 抛 IllegalArgumentException。
 * 无 metricCode，不读 metric。
 */
public class OccurredAtEvaluator implements ConditionEvaluator {

    @Override
    public boolean evaluate(ConditionNode node, EvalContext ctx) {
        Instant occurred = ctx.event() != null ? ctx.event().occurredAt() : null;
        if (occurred == null) return false;

        Map<String, Object> params = node.params();
        String operator = (String) params.get("operator");
        if (operator == null) return false;

        ZoneId zone = TimeZoneResolver.resolve((String) params.get(ConditionParams.TIMEZONE),
                (String) ctx.sceneDefaultParams().get(SceneDefaultParams.TIMEZONE));

        switch (operator) {
            case "BEFORE": return occurred.isBefore(required(params.get("value"), ctx, zone));
            case "AFTER":  return occurred.isAfter(required(params.get("value"), ctx, zone));
            case "BETWEEN": {
                Instant start = required(params.get("start"), ctx, zone);
                Instant end   = required(params.get("end"), ctx, zone);
                // 闭区间两端均包含
                return !occurred.isBefore(start) && !occurred.isAfter(end);
            }
            default: throw new IllegalArgumentException("time.occurred_at 未知 operator: " + operator);
        }
    }

    /**
     * 解析必填时间操作数；无法解析（含 $today / 解析失败）抛 IllegalArgumentException → CONDITION_EVAL_ERROR。
     */
    private static Instant required(Object raw, EvalContext ctx, ZoneId zone) {
        Instant v = PlaceholderResolver.resolveDateTime(raw, ctx, zone);
        if (v == null) {
            throw new IllegalArgumentException("time.occurred_at 无法解析时间操作数: " + raw);
        }
        return v;
    }
}
