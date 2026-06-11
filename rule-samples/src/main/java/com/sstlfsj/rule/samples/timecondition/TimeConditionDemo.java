package com.sstlfsj.rule.samples.timecondition;

import com.sstlfsj.rule.kernel.api.model.EvalResult;
import com.sstlfsj.rule.kernel.api.model.EventSource;
import com.sstlfsj.rule.kernel.api.model.RuleEvent;
import com.sstlfsj.rule.sdk.RuleEngineClient;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 时间条件示例:演示内置时间算子的两种写法(规则放本地 JSON,零服务依赖)。
 * <ul>
 *   <li><b>time.occurred_at</b> —— 比较事件发生时间 {@code event.occurredAt()},operator
 *       BEFORE/AFTER/BETWEEN;固定 occurredAt → 结果确定。</li>
 *   <li><b>time.window</b> —— 判断<b>当前评估时刻</b> {@code ctx.now()} 是否落在营业时段
 *       (工作日 09:00-18:00 Asia/Shanghai);<b>结果随运行时刻变化</b>。</li>
 * </ul>
 * <p>两个时间算子由 kernel 默认注册,SDK 本地评估直接可用,无需自定义算子。
 * <p>怎么跑:{@code $MVN -pl rule-samples exec:java -Dexec.mainClass="com.sstlfsj.rule.samples.timecondition.TimeConditionDemo"}
 */
public final class TimeConditionDemo {

    private TimeConditionDemo() {
    }

    public static void main(String[] args) {
        try (RuleEngineClient client = RuleEngineClient.builder()
                .ruleFile("rules/time-conditions.json")
                .build()) {

            // 1) time.occurred_at:事件发生时间是否落在 2026 全年区间(BETWEEN)——确定性
            EvalResult in2026 = client.evaluate(login(Instant.parse("2026-06-11T10:00:00Z")));
            EvalResult in2025 = client.evaluate(login(Instant.parse("2025-06-11T10:00:00Z")));
            System.out.println("[time] occurred_at occurredAt=2026-06-11 → hit=" + in2026.ruleHit());
            System.out.println("[time] occurred_at occurredAt=2025-06-11 → hit=" + in2025.ruleHit());

            // 2) time.window:营业时段(周一~五 09:00-18:00 Asia/Shanghai)——取决于当前运行时刻
            EvalResult window = client.evaluate(access());
            System.out.println("[time] time.window now=" + Instant.now() + " → hit=" + window.ruleHit()
                    + " (工作日 09:00-18:00 之外为 false)");
        }
    }

    private static RuleEvent login(Instant occurredAt) {
        return new RuleEvent(
                "9100", "time-demo", "login", "user-1",
                UUID.randomUUID().toString(), occurredAt,
                Map.of(), Map.of(), EventSource.SDK);
    }

    private static RuleEvent access() {
        return new RuleEvent(
                "9100", "time-demo", "access", "user-1",
                UUID.randomUUID().toString(), Instant.now(),
                Map.of(), Map.of(), EventSource.SDK);
    }
}
