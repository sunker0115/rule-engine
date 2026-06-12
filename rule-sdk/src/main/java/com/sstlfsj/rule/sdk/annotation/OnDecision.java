package com.sstlfsj.rule.sdk.annotation;

import java.lang.annotation.*;

/** 标在处理器方法上,按 decision code 订阅;命中对应决策时被调用,参数支持 @Fact/@Metric 注入。 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface OnDecision {
    /** 订阅的 decision code 列表。 */
    String[] value();

    /**
     * 仅当决策由该 fromRuleCode 规则产出时才触发;留空(默认)= 不限来源规则。
     * 用于多条规则绑定同一 decision code 时,把处理器精确绑定到某条规则。
     */
    String fromRuleCode() default "";
}
