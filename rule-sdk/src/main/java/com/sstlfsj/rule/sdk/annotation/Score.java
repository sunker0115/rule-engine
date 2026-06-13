package com.sstlfsj.rule.sdk.annotation;

import java.lang.annotation.*;

/** 标在规则方法上,返回 double 评分;经方法上的 @ScoreBand 分档映射到决策并写入 EvalResult.score。与 @Condition/@Decide 互斥。 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Score {}
