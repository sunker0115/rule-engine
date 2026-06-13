package com.sstlfsj.rule.kernel.api.operator;

import com.sstlfsj.rule.kernel.api.model.ConditionParams;
import com.sstlfsj.rule.kernel.api.model.DataType;

import java.util.Set;

/**
 * 算子参数规格预设：将常见的"必填键 + 允许 dataType + 是否需 metric"组合具名化。
 *
 * <p>19 个内置算子共用 11 种预设，通过 {@link com.sstlfsj.rule.kernel.api.annotation.ConditionType#schema()}
 * 声明；自定义算子可选 {@link #NONE}（纯展示，无参数校验）或注册 {@code @Bean OperatorSpec}（完全控制）。
 *
 * <p>字段直接存 String tag，{@link com.sstlfsj.rule.kernel.internal.condition.ConditionTypeCatalog}
 * 读取时无需转换。
 */
public enum ParamSpec {

    /** 数值比较（GT/GTE/LT/LTE）：threshold，LONG·DOUBLE·DECIMAL，需 metric。 */
    NUMERIC(
            Set.of(ConditionParams.THRESHOLD),
            Set.of(DataType.LONG.tag(), DataType.DOUBLE.tag(), DataType.DECIMAL.tag()),
            true),

    /** 等值比较（EQ/NEQ）：threshold，全类型，需 metric。 */
    ANY_TYPE(
            Set.of(ConditionParams.THRESHOLD),
            Set.of(DataType.LONG.tag(), DataType.DOUBLE.tag(), DataType.DECIMAL.tag(),
                    DataType.STRING.tag(), DataType.BOOLEAN.tag(),
                    DataType.DATE.tag(), DataType.DATETIME.tag()),
            true),

    /** 区间比较（BETWEEN/NOT_BETWEEN）：min+max，数值+日期，需 metric。 */
    BETWEEN_RANGE(
            Set.of(ConditionParams.MIN, ConditionParams.MAX),
            Set.of(DataType.LONG.tag(), DataType.DOUBLE.tag(), DataType.DECIMAL.tag(),
                    DataType.DATE.tag(), DataType.DATETIME.tag()),
            true),

    /** 集合成员（IN/NOT_IN）：values，LONG·STRING，需 metric。 */
    IN_COLLECTION(
            Set.of(ConditionParams.VALUES),
            Set.of(DataType.LONG.tag(), DataType.STRING.tag()),
            true),

    /** LIST 包含（CONTAINS/NOT_CONTAINS）：element，LIST，需 metric。 */
    LIST_MEMBERSHIP(
            Set.of(ConditionParams.ELEMENT),
            Set.of(DataType.LIST.tag()),
            true),

    /** 字符串前缀（STARTS_WITH）：prefix，STRING，需 metric。 */
    STRING_PREFIX(
            Set.of(ConditionParams.PREFIX),
            Set.of(DataType.STRING.tag()),
            true),

    /** 字符串后缀（ENDS_WITH）：suffix，STRING，需 metric。 */
    STRING_SUFFIX(
            Set.of(ConditionParams.SUFFIX),
            Set.of(DataType.STRING.tag()),
            true),

    /** 正则匹配（MATCHES）：regex，STRING，需 metric。 */
    STRING_REGEX(
            Set.of(ConditionParams.REGEX),
            Set.of(DataType.STRING.tag()),
            true),

    /** 日期比较（DATE_BEFORE/DATE_AFTER）：threshold，DATE·DATETIME，需 metric。 */
    DATE_COMPARE(
            Set.of(ConditionParams.THRESHOLD),
            Set.of(DataType.DATE.tag(), DataType.DATETIME.tag()),
            true),

    /** 时间窗口（time.window）：start+end，无 dataType 约束，不需 metric。 */
    TIME_WINDOW_OP(
            Set.of(ConditionParams.START, ConditionParams.END),
            Set.of(),
            false),

    /** 事件时间（time.occurred_at）：operator，无 dataType 约束，不需 metric。 */
    TIME_OCCURRED_OP(
            Set.of(ConditionParams.OPERATOR),
            Set.of(),
            false),

    /**
     * 无预设（自定义算子默认）：无必填键约束，发布期放行；
     * 需参数校验的自定义算子应注册 {@code @Bean OperatorSpec}。
     */
    NONE(Set.of(), Set.of(), true);

    /** 必填 param 键（{@link ConditionParams} 常量值）。 */
    public final Set<String> requiredParamKeys;
    /** 允许的 metric/payload dataType（{@link DataType} tag 值）。 */
    public final Set<String> allowedDataTypes;
    /** 是否需要绑定 metric/payload 字段。 */
    public final boolean requiresMetric;

    ParamSpec(Set<String> requiredParamKeys, Set<String> allowedDataTypes, boolean requiresMetric) {
        this.requiredParamKeys = requiredParamKeys;
        this.allowedDataTypes = allowedDataTypes;
        this.requiresMetric = requiresMetric;
    }
}
