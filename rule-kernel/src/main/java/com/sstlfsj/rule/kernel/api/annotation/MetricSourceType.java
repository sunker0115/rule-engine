package com.sstlfsj.rule.kernel.api.annotation;

import java.lang.annotation.*;

/** 标注 MetricSourceHandler 实现类的指标来源类型标识与元数据。 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface MetricSourceType {
    String value();
    String paramsSchema() default "{}";
    int defaultTimeoutMs() default 1000;
    int defaultCacheTtlSeconds() default 60;
}
