package com.sstlfsj.rule.observability.internal.alarm;

import com.sstlfsj.rule.observability.api.events.EvalAlarmEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;

/**
 * 告警事件监听器（v1）：收到 {@link EvalAlarmEvent} 打 WARN 日志。
 * 替换此 bean 或新增额外 @EventListener 即可扩展 Webhook / 钉钉等通道。
 * 由 ObservabilityAutoConfiguration 显式注册（不加 @Component，避免双重注册）。
 */
public class ObservabilityAlarmListener {

    private static final Logger log = LoggerFactory.getLogger(ObservabilityAlarmListener.class);

    @EventListener
    public void onAlarm(EvalAlarmEvent event) {
        log.warn("[RULE_ALARM] metric={} actual={} threshold={} msg={}",
                event.metric(), event.actual(), event.threshold(), event.message());
    }
}
