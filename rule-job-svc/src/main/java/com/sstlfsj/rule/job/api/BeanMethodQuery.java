package com.sstlfsj.rule.job.api;

/**
 * BEAN_METHOD 型主体查询：反射调用 {@code ref}（{@code <bean>#<method>}）指向的 @RuleJob 方法。
 *
 * @param ref Spring bean 名 + 方法名，格式 {@code <bean>#<method>}
 */
public record BeanMethodQuery(String ref) implements SubjectQuery {}
