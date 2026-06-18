package com.sstlfsj.rule.eval.internal.pregate;

import com.sstlfsj.rule.kernel.api.model.PreGateContext;
import com.sstlfsj.rule.kernel.api.model.PreGateResult;
import com.sstlfsj.rule.kernel.api.spi.pregate.PreGate;
import org.springframework.stereotype.Component;

/**
 * TIME_WINDOW Pre-Gate：按规则生效时段放行。窗口外拦截，使规则到点自动生效/失效，
 * 无需人工 publish/disable，也无需把时间塞进规则条件 AST。
 *
 * <p>参数（params）：
 * <ul>
 *   <li>{@code fromEpochMilli}：生效起（epoch millis，含）；缺省表示无起始约束。</li>
 *   <li>{@code toEpochMilli}：生效止（epoch millis，含）；缺省表示无结束约束。</li>
 * </ul>
 *
 * <p>判断用 {@link PreGateContext#occurredAt()}（引擎统一评估时刻，重放/asOf 注入历史时刻，保证可复现），
 * 闭区间 {@code [from, to]} 命中即放行。两者皆缺省时 fail-open（全量放行，对齐 {@link RolloutPreGate} 未配置即放行）。
 */
@Component
public class TimeWindowPreGate implements PreGate {

    @Override
    public String gateType() {
        return "TIME_WINDOW";
    }

    @Override
    public PreGateResult evaluate(PreGateContext ctx) {
        Long from = toLong(ctx.gateParams().get("fromEpochMilli"));
        Long to = toLong(ctx.gateParams().get("toEpochMilli"));

        // 起止都未配置：视为无时段约束，全量放行
        if (from == null && to == null) {
            return PreGateResult.pass();
        }
        long ts = ctx.occurredAt().toEpochMilli();
        // 闭区间 [from, to]；单边缺省即该侧无约束
        if (from != null && ts < from) {
            return PreGateResult.blocked("TIME_WINDOW");
        }
        if (to != null && ts > to) {
            return PreGateResult.blocked("TIME_WINDOW");
        }
        return PreGateResult.pass();
    }

    /** 容错解析 epoch millis：Number 取 longValue，String 去空白后解析，null/空返回 null。 */
    private static Long toLong(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.longValue();
        String s = v.toString().trim();
        return s.isEmpty() ? null : Long.parseLong(s);
    }
}
