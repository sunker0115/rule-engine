package com.sstlfsj.rule.sdk.annotation;

import java.lang.annotation.*;

/** 标在规则 POJO 的布尔方法上,声明该规则的条件;一个 @RuleDef 规则恰好一个。 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Condition {}
