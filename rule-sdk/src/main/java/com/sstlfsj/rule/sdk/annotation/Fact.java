package com.sstlfsj.rule.sdk.annotation;

import java.lang.annotation.*;

/** 注入参数:从 event.payload 取,取不到回退元数据(eventId/tenantId/.../决策码);不涉及 metric。 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Fact {
    /** payload 字段名 / 元数据键名;留空则回退方法参数名(需编译期 -parameters)。 */
    String value() default "";
    /** 取不到(payload+元数据皆无)时是否报错;默认 false=注入默认值/null。 */
    boolean required() default false;
    /** 取不到时的回退字面量;非空则按参数类型解析注入(优先级低于实际取值,高于 null)。 */
    String defaultValue() default "";
}
