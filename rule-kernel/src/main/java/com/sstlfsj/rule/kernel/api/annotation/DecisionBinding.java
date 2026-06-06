package com.sstlfsj.rule.kernel.api.annotation;

import java.lang.annotation.*;

/** `@RuleDef.decisions` 的嵌套注解，声明规则命中时绑定的 Decision。 */
@Target({})
@Retention(RetentionPolicy.RUNTIME)
public @interface DecisionBinding {
    /** Decision 编码，与 Decision 实体 code 对应。 */
    String code();
    /** 优先级，值越大优先级越高，默认 0。 */
    int priority() default 0;
}
