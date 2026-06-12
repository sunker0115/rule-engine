package com.sstlfsj.rule.sdk.annotation;

import java.lang.annotation.*;

/**
 * 标在规则 POJO 的方法上,返回命中的 decision code(null/空=不命中);
 * 返回值须是 @RuleDef.decisions 声明的码之一。返回 List&lt;String&gt;/String[] 可一次发多个决策。
 * 与 @Condition/@Score 互斥,一个规则恰好一个判定原语。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Decide {}
