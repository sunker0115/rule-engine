package com.sstlfsj.rule.kernel.api.annotation;

import java.lang.annotation.*;

/** 标注 ConditionEvaluator 实现类的条件类型标识与元数据。 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ConditionType {
    String value();
    String displayName() default "";
    String paramsSchema() default "{}";
}
