package com.sstlfsj.rule.kernel.api.annotation;

import java.lang.annotation.*;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ConditionType {
    String value();
    String displayName() default "";
    String paramsSchema() default "{}";
    boolean requiresMetric() default false;
}
