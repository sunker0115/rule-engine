package com.sstlfsj.rule.kernel.api.annotation;

import java.lang.annotation.*;

/** 标注 ConditionEvaluator 实现类的条件类型标识与元数据。 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ConditionType {
    /** 条件类型标识,全局唯一;= 规则 AST {@code ConditionNode.conditionType} / evaluator 注册键(如 "GT"、"geo.within")。 */
    String value();
    /** 展示名,供前端/元数据接口显示;留空回退到 {@link #value()}。 */
    String displayName() default "";
    /** 参数 schema(JSON 字符串),供前端渲染参数输入与发布期校验;默认空对象表示无参数。 */
    String paramsSchema() default "{}";
}
