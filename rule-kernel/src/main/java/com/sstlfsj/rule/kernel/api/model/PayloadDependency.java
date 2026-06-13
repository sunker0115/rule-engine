package com.sstlfsj.rule.kernel.api.model;

import lombok.Builder;

import java.util.List;

/**
 * 规则引用的 payload 字段依赖(发布期从 scene.payloadSchema 冻结,随 RuleVersionSnapshot 下发)。
 * 携带完整输入约束,供评估期 PayloadInputValidator 强制校验(模型 2:约束随规则发布冻结)。
 *
 * @param name       payload 字段名(== ConditionNode.metricCode,valueRef=PAYLOAD)
 * @param dataType   字段类型标签(DataType.tag())
 * @param required   是否必填
 * @param enumValues 枚举约束(null=不约束)
 * @param minimum    数值下界(null=不约束)
 * @param maximum    数值上界(null=不约束)
 * @param pattern    正则约束(null=不约束)
 */
@Builder
public record PayloadDependency(String name, String dataType, boolean required,
                                List<Object> enumValues, Double minimum, Double maximum, String pattern) {

    /** 兼容构造器:无约束(既有 3 参老调用点专用),约束字段全 null。 */
    public PayloadDependency(String name, String dataType, boolean required) {
        this(name, dataType, required, null, null, null, null);
    }
}
