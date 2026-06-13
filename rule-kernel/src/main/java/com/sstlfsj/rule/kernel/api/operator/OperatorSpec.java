package com.sstlfsj.rule.kernel.api.operator;

import lombok.Builder;
import java.util.Set;

/**
 * 算子规格:必填 param 键 + 允许 dataType + 元数据,供发布期校验与元数据暴露使用。
 * 由 {@link com.sstlfsj.rule.kernel.api.spi.condition.ConditionEvaluator#spec()} 声明,
 * 算子契约与实现共同演进(单一真相源)。
 *
 * @param code             conditionType 编码
 * @param displayName      运营可读名
 * @param requiredParamKeys 必填 param 键({@link com.sstlfsj.rule.kernel.api.model.ConditionParams} 常量)
 * @param allowedDataTypes  允许的 metric/payload dataType({@link com.sstlfsj.rule.kernel.api.model.DataType} tag)
 * @param requiresMetric    是否需要绑定 metric/payload 字段(time.* 内置路径为 false)
 */
@Builder
public record OperatorSpec(String code, String displayName,
                           Set<String> requiredParamKeys,
                           Set<String> allowedDataTypes,
                           boolean requiresMetric) {}
