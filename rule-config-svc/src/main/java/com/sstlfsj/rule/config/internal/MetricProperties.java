package com.sstlfsj.rule.config.internal;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** metric 定义写入相关配置。 */
@Getter
@Setter
@ConfigurationProperties(prefix = "engine.rule.metric")
public class MetricProperties {

    /** metric 未显式指定缓存 TTL 时的默认值（秒，默认 60）。 */
    private int defaultCacheTtlSeconds = 60;
}
