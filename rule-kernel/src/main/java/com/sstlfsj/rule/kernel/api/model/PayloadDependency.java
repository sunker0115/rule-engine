package com.sstlfsj.rule.kernel.api.model;

/**
 * 规则引用的 payload 字段依赖(发布期从 scene.payloadSchema 冻结,随 RuleVersionSnapshot 下发)。
 * 与 MetricDependency 对称:metric 是受治理指标依赖,payload 是事件事实输入契约。
 *
 * @param name     payload 字段名(== ConditionNode.metricCode,valueRef=PAYLOAD 时复用为字段名)
 * @param dataType 字段类型标签(DataType.tag(),由 payloadSchema type 经 PayloadDataTypeMapper 映射冻结)
 * @param required 是否必填(取自 payloadSchema 字段声明)
 */
public record PayloadDependency(String name, String dataType, boolean required) {
}
