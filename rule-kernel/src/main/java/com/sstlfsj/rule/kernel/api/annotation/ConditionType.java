package com.sstlfsj.rule.kernel.api.annotation;

import com.sstlfsj.rule.kernel.api.operator.ParamSpec;

import java.lang.annotation.*;

/**
 * 标注 ConditionEvaluator 实现类的条件类型标识与元数据。
 * 供 {@link com.sstlfsj.rule.kernel.internal.condition.ConditionTypeCatalog} 收集，
 * 同时作为自定义算子向元数据接口暴露自身规格的入口。
 *
 * <p>参数规格通过 {@link #schema()} 引用 {@link ParamSpec} 枚举常量——具名预设，
 * 比散落的数组字面量更 DRY、意图更清晰；自定义算子用 {@link ParamSpec#NONE} 或注册
 * {@code @Bean OperatorSpec}（完全控制）。
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ConditionType {
    /** conditionType 编码，全局唯一（= ConditionNode.conditionType / evaluator 注册键）。 */
    String value();
    /** 运营可读名；留空回退到 {@link #value()}。 */
    String displayName() default "";
    /** 参数规格预设（必填键 + 允许 dataType + 是否需 metric）；默认 {@link ParamSpec#NONE}。 */
    ParamSpec schema() default ParamSpec.NONE;
}
