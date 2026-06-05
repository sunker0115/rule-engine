package com.sstlfsj.rule.kernel.internal.condition.time;

import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * 时区解析序（B20 §2）：params.timezone &gt; sceneDefaultTimezone &gt; UTC。
 * 字面量自带 offset（优先级1）由各 evaluator 在解析具体值时处理，不经本工具。
 * sceneDefaultTimezone 当前一律为 null（Scene 级管线延后），槽位预留。
 */
public final class TimeZoneResolver {

    private TimeZoneResolver() {}

    /**
     * 解析生效时区。
     *
     * @param paramsTimezone       条件节点 params.timezone（IANA 名），可为 null/空白
     * @param sceneDefaultTimezone 场景默认时区（IANA 名），当前恒为 null，可为 null/空白
     * @return 解析得到的 ZoneId，兜底 UTC
     */
    public static ZoneId resolve(String paramsTimezone, String sceneDefaultTimezone) {
        if (paramsTimezone != null && !paramsTimezone.isBlank()) {
            return ZoneId.of(paramsTimezone.trim());
        }
        if (sceneDefaultTimezone != null && !sceneDefaultTimezone.isBlank()) {
            return ZoneId.of(sceneDefaultTimezone.trim());
        }
        return ZoneOffset.UTC;
    }
}
