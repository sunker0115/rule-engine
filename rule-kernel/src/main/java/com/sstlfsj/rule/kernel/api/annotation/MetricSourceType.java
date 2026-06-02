package com.sstlfsj.rule.kernel.api.annotation;

import java.lang.annotation.*;

/** Marks a MetricSourceHandler implementation with its metric source type identifier and metadata. */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface MetricSourceType {
    String value();
    String paramsSchema() default "{}";
    int defaultTimeoutMs() default 1000;
    int defaultCacheTtlSeconds() default 60;
}
