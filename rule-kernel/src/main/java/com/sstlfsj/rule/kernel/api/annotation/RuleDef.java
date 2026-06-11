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
    /** 规则逻辑编码，= 配置路径 rule.code，(tenant,scene) 内唯一。 */
    String code();
    /** 场景编码。 */
    String sceneCode();
    /** 租户 ID；空 = 用 RuleEngineClient 配置的租户。 */
    String tenantId() default "";
    /** 版本号，代码定义规则默认 1。 */
    long version() default 1L;
    /** 触发事件类型；空数组表示通配（装载时写入 "*"）。 */
    String[] trigger() default {};
    /** Decision 绑定列表。 */
    DecisionBinding[] decisions() default {};
}
