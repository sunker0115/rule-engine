package com.sstlfsj.rule.sdk.annotation;

import java.lang.annotation.*;

/**
 * 标在方法上声明一个 metric 的取数逻辑(供给侧),= 消费侧 @Metric 的值。
 * 扫描器把方法合成 MetricSourceHandler + 自动生成 MetricDescriptor,无需实现接口、无需单独写定义。
 * 可标在任意 Spring bean(含规则类:metric 私有于该规则时)。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface MetricSource {
    /** metric 编码,(tenant 内)全局唯一。 */
    String value();
    /** 取数结果缓存 ttl 秒;0 = 不缓存。 */
    int cacheTtlSeconds() default 0;
    /** 是否允许调用方推值(providedMetrics)覆盖 fetch;默认 false=恒走本方法。 */
    boolean allowProvided() default false;
}
