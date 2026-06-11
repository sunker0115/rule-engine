package com.sstlfsj.rule.eval.internal;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 计算闸/全局 NodeTrace 收集开关配置。
 *
 * <p>仅绑定 {@code engine.rule.trace.enabled} 一个字段；TraceWriter 的调优字段
 * （队列容量/批大小/刷盘间隔）由 observability 模块单独绑同前缀的另一个 properties。
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "engine.rule.trace")
public class TraceProperties {

    /** 全局 NodeTrace 收集开关（默认 true）；false 时计算闸关闭 trace 收集。 */
    private boolean enabled = true;
}
