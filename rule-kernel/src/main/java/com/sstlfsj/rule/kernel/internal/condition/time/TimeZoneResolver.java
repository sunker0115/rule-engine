package com.sstlfsj.rule.kernel.internal.condition.time;

import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * 时区解析序（B20 §2）：params.timezone &gt; sceneDefaultTimezone &gt; UTC。
 * 字面量自带 offset（优先级1）由各 evaluator 在解析具体值时处理，不经本工具。
 * scene 默认值非法时兜底 UTC（脏配置不应让运行期抛错）。
 */
public final class TimeZoneResolver {

    private TimeZoneResolver() {}

    /**
     * 解析生效时区。
     *
     * @param paramsTimezone       条件节点 params.timezone（IANA 名），可为 null/空白；非法时抛出（语义不变）
     * @param sceneDefaultTimezone 场景默认时区（IANA 名），可为 null/空白；非法时兜底 UTC
     * @return 解析得到的 ZoneId，兜底 UTC
     */
    public static ZoneId resolve(String paramsTimezone, String sceneDefaultTimezone) {
        if (paramsTimezone != null && !paramsTimezone.isBlank()) {
            return ZoneId.of(paramsTimezone.trim());
        }
        if (sceneDefaultTimezone != null && !sceneDefaultTimezone.isBlank()) {
            try {
                return ZoneId.of(sceneDefaultTimezone.trim());
            } catch (RuntimeException e) {
                return ZoneOffset.UTC;
            }
        }
        return ZoneOffset.UTC;
    }
}
