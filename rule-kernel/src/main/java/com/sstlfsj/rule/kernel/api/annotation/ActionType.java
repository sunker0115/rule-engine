package com.sstlfsj.rule.kernel.api.annotation;

import java.lang.annotation.*;

/** Marks an ActionHandler implementation with its action type identifier and metadata. */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ActionType {
    String value();
    String displayName() default "";
    String paramsSchema() default "{}";
    int timeoutMs() default 3000;
    boolean compensatable() default false;
}
