package com.sstlfsj.rule.kernel.api.annotation;

import java.lang.annotation.*;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface MetricSourceType {
    String value();
    String paramsSchema() default "{}";
    int defaultTimeoutMs() default 1000;
    int defaultCacheTtlSeconds() default 60;
}
