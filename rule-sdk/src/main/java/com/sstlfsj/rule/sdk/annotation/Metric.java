package com.sstlfsj.rule.sdk.annotation;

import java.lang.annotation.*;

/**
 * 注入 metric 值。标在 @Condition 参数=声明依赖(驱动预拉)+取值;标在 @OnDecision 参数=仅取值(同 context 查,查不到 null)。
 * version 在嵌入式 SDK 恒为 1,无需填写。
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Metric {
    /** metric 编码。 */
    String value();
    /** metric 版本,SDK 默认 1。 */
    int version() default 1;
}
