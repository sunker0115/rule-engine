package com.sstlfsj.rule.sdk;

/**
 * 注解模式规则 SPI：实现此接口并标注 {@link com.sstlfsj.rule.kernel.api.annotation.RuleDef}，
 * 由 {@link com.sstlfsj.rule.sdk.source.AnnotationRuleSource} 扫描后装载到评估索引。
 */
public interface InlineRuleSpec {
    /** 返回规则条件，AnnotationRuleSource 调用 toAst() 转为 AST 节点。 */
    Condition condition();
}
