package com.sstlfsj.rule.kernel.api.annotation;

import java.lang.annotation.*;

/**
 * 标注规则定义类（InlineRuleSpec 实现），声明规则元数据。
 * 与 InlineRuleSpec.condition() 配合，由 AnnotationRuleSource 装载到评估索引。
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RuleDef {
    /** 规则版本 ID，调用方负责唯一且稳定，AnnotationRuleSource 幂等写入依赖此值。 */
    long id();
    /** 租户 ID。 */
    String tenantId();
    /** 场景编码。 */
    String sceneCode();
    /** 触发事件类型；空数组表示通配（装载时写入 "*"）。 */
    String[] trigger() default {};
    /** Decision 绑定列表。 */
    DecisionBinding[] decisions() default {};
}
