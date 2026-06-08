package com.sstlfsj.rule.eval.internal.action;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** action 幂等去重缓存配置。 */
@Getter
@Setter
@ConfigurationProperties(prefix = "engine.rule.action.idempotency")
public class ActionIdempotencyProperties {
    /** 去重窗口 TTL 秒（默认 600）；超过窗口的重复 eventId 不再去重。 */
    private long ttlSeconds = 600;
    /** 缓存最大键数（默认 100000）。 */
    private long maxSize = 100_000;
}
