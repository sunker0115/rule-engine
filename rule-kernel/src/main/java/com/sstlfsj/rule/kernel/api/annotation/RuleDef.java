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
    /**
     * SDK 默认场景码。未显式指定 {@link #sceneCode()} 时用之。
     * 事件须用同名 sceneCode（{@code RuleDef.DEFAULT_SCENE}）才能匹配到默认场景的规则。
     */
    String DEFAULT_SCENE = "default";

    /** 规则逻辑编码，= 配置路径 rule.code，(tenant,scene) 内唯一。 */
    String code();
    /** 场景编码；缺省 = {@link #DEFAULT_SCENE}（事件须用同名 sceneCode 匹配）。 */
    String sceneCode() default DEFAULT_SCENE;
    /** 租户 ID；空 = 用 RuleEngineClient 配置的租户。 */
    String tenantId() default "";
    /** 版本号，代码定义规则默认 1。 */
    long version() default 1L;
    /**
     * 触发的事件类型（可多个），与 {@code scene.eventTypes} 白名单 / {@code RuleEvent.eventType} 同一套词汇。
     * 空数组 = 通配（装载时写入 "*"，匹配任意 eventType）。
     */
    String[] eventTypes() default {};
    /** Decision 绑定列表。 */
    DecisionBinding[] decisions() default {};
}
