package com.sstlfsj.rule.kernel.api.annotation;

import com.sstlfsj.rule.kernel.api.model.DataType;
import java.lang.annotation.*;

/**
 * 标注 ConditionEvaluator 实现类的条件类型标识与元数据。
 * 供 {@link com.sstlfsj.rule.kernel.internal.condition.ConditionTypeCatalog} 收集，
 * 同时作为自定义算子向元数据接口暴露自身规格的入口。
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ConditionType {
    /** conditionType 编码，全局唯一（= ConditionNode.conditionType / evaluator 注册键）。 */
    String value();
    /** 运营可读名；留空回退到 {@link #value()}。 */
    String displayName() default "";
    /** 必填 param 键（{@link com.sstlfsj.rule.kernel.api.model.ConditionParams} 常量）。 */
    String[] requiredParamKeys() default {};
    /** 允许的 metric/payload dataType（DataType 枚举，编译期常量）。 */
    DataType[] allowedDataTypes() default {};
    /** 是否需要绑定 metric/payload 字段（time.* 内置路径为 false）。 */
    boolean requiresMetric() default true;
}
