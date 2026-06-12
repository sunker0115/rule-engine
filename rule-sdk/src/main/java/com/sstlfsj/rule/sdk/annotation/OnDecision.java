package com.sstlfsj.rule.sdk.annotation;

import java.lang.annotation.*;

/**
 * 标在处理器方法上,按 decision code 订阅;命中对应决策时被调用,参数支持 @Fact/@Metric 注入。
 * <p><b>线程语义</b>:默认 {@code async=false},处理器在评估线程(RuleEngineClient.evaluate 调用栈)内
 * 同步执行,慢处理器会阻塞评估返回;置 {@code async=true} 则交由 starter 的独立线程池异步执行。
 */
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

    /** true=处理器在独立线程池异步执行,不阻塞评估;默认 false=评估线程同步执行。 */
    boolean async() default false;
}
