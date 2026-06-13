package com.sstlfsj.rule.kernel.internal.condition;

import com.sstlfsj.rule.kernel.api.annotation.ConditionType;
import com.sstlfsj.rule.kernel.api.model.ConditionParams;
import com.sstlfsj.rule.kernel.api.model.ConditionTypes;
import com.sstlfsj.rule.kernel.api.model.EvalContext;
import com.sstlfsj.rule.kernel.api.model.SceneDefaultParams;
import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;
import com.sstlfsj.rule.kernel.api.operator.ParamSpec;
import com.sstlfsj.rule.kernel.api.spi.condition.ConditionEvaluator;
import com.sstlfsj.rule.kernel.internal.condition.time.TimeZoneResolver;

import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

/**
 * time.window 内置条件：判断 ctx.now() 投影到解析时区后的墙上时间是否落在生效时段（B20 §3）。
 * 过滤序：datesExclude（节假日，整条件 false）→ daysOfWeek（允许列表）→ [start,end] 闭区间（支持跨午夜）。
 * 无 metricCode，不读 metric。
 */
@ConditionType(value = ConditionTypes.TIME_WINDOW, displayName = "时间窗口", schema = ParamSpec.TIME_WINDOW_OP)
public class TimeWindowEvaluator implements ConditionEvaluator {

    @Override
    public boolean evaluate(ConditionNode node, EvalContext ctx) {
        Map<String, Object> params = node.params();
        Object startRaw = params.get(ConditionParams.START);
        Object endRaw = params.get(ConditionParams.END);
        if (startRaw == null || endRaw == null) return false;

        ZoneId zone = TimeZoneResolver.resolve((String) params.get(ConditionParams.TIMEZONE),
                (String) ctx.sceneDefaultParams().get(SceneDefaultParams.TIMEZONE));
        ZonedDateTime zdt = ctx.now().atZone(zone);

        // 1. datesExclude（MM-DD）优先短路
        List<String> excl = asStringList(params.get(ConditionParams.DATES_EXCLUDE));
        String mmdd = String.format("%02d-%02d", zdt.getMonthValue(), zdt.getDayOfMonth());
        if (excl.contains(mmdd)) return false;

        // 2. daysOfWeek（MON..SUN，取 DayOfWeek 名前三字母）
        List<String> dows = asStringList(params.get(ConditionParams.DAYS_OF_WEEK));
        if (!dows.isEmpty()) {
            String dow = zdt.getDayOfWeek().name().substring(0, 3);
            if (!dows.contains(dow)) return false;
        }

        // 3. [start, end] 闭区间；end < start 表示跨午夜
        LocalTime start = LocalTime.parse((String) startRaw);
        LocalTime end = LocalTime.parse((String) endRaw);
        LocalTime t = zdt.toLocalTime();
        if (end.isBefore(start)) {
            // 跨午夜：t >= start 或 t <= end
            return !t.isBefore(start) || !t.isAfter(end);
        }
        return !t.isBefore(start) && !t.isAfter(end);
    }

    @SuppressWarnings("unchecked")
    private static List<String> asStringList(Object raw) {
        if (raw instanceof List<?> list) return (List<String>) list;
        return List.of();
    }
}
