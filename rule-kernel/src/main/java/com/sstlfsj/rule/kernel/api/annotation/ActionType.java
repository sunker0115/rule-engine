package com.sstlfsj.rule.kernel.api.annotation;

import java.lang.annotation.*;

/** 标注 ActionHandler 实现类的动作类型标识与元数据。 */
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
