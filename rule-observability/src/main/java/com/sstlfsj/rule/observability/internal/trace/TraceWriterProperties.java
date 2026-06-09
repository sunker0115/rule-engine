package com.sstlfsj.rule.observability.internal.trace;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * TraceWriter 异步批写调优配置：队列容量 / 批大小 / 刷盘间隔。
 *
 * <p>与 eval-svc 的 TraceProperties 共用 {@code engine.rule.trace} 前缀但绑不同字段
 * （Spring 允许同前缀多个 properties 各绑子集）；observability 不依赖 eval-svc，故自带一份。
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "engine.rule.trace")
public class TraceWriterProperties {

    /** 异步队列容量（默认 10000）。 */
    private int queueCapacity = 10000;
    /** 单批落库最大条数（默认 500）。 */
    private int batchSize = 500;
    /** 刷盘间隔毫秒（默认 200）。 */
    private long flushIntervalMs = 200;
}
