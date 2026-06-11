package com.sstlfsj.rule.sdk.annotation;

import java.lang.annotation.*;

/** 注入参数:从 event.payload 取,取不到回退元数据(eventId/tenantId/.../决策码);不涉及 metric。 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Fact {
    /** payload 字段名 / 元数据键名。 */
    String value();
}
