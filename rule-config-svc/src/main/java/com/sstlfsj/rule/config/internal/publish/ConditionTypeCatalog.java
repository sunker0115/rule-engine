package com.sstlfsj.rule.config.internal.publish;

import com.sstlfsj.rule.kernel.api.model.ConditionParams;
import com.sstlfsj.rule.kernel.api.model.ConditionTypes;
import com.sstlfsj.rule.kernel.api.model.DataType;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 内置条件算子目录：每个算子的必填 param 键 + 允许 dataType + 是否需要 metric + 显示名，单一真相源。
 * 三处共用：发布期 {@link ConditionParamValidator}（必填键）、{@link AstDataTypeResolver}（允许 dataType）、
 * MetadataService（前端算子目录 + paramsSchema）。
 * conditionType 是 SPI 开放集：目录缺席的算子（time.* 内置路径、自定义算子）一律放行/不暴露元数据。
 */
public final class ConditionTypeCatalog {

    private ConditionTypeCatalog() {}

    /**
     * 单个算子规格。
     *
     * @param code             conditionType 编码
     * @param displayName      运营可读名
     * @param requiredParamKeys 必填 param 键（{@link ConditionParams} 常量）
     * @param allowedDataTypes 允许的 metric/payload dataType（{@link DataType} tag）
     * @param requiresMetric   是否需要绑定 metric/payload 字段
     */
    @lombok.Builder
    public record Spec(String code, String displayName, Set<String> requiredParamKeys,
                       Set<String> allowedDataTypes, boolean requiresMetric) {}

    private static final Map<String, Spec> CATALOG = build();

    private static Map<String, Spec> build() {
        Map<String, Spec> m = new LinkedHashMap<>();
        Set<String> numeric = Set.of(DataType.LONG.tag(), DataType.DOUBLE.tag(), DataType.DECIMAL.tag());
        Set<String> numericDate = Set.of(DataType.LONG.tag(), DataType.DOUBLE.tag(), DataType.DECIMAL.tag(),
                DataType.DATE.tag(), DataType.DATETIME.tag());
        Set<String> eqTypes = Set.of(DataType.LONG.tag(), DataType.DOUBLE.tag(), DataType.DECIMAL.tag(),
                DataType.STRING.tag(), DataType.BOOLEAN.tag(), DataType.DATE.tag(), DataType.DATETIME.tag());
        Set<String> inTypes = Set.of(DataType.LONG.tag(), DataType.STRING.tag());
        Set<String> list = Set.of(DataType.LIST.tag());
        Set<String> string = Set.of(DataType.STRING.tag());
        Set<String> date = Set.of(DataType.DATE.tag(), DataType.DATETIME.tag());

        put(m, ConditionTypes.EQ,           "等于",       Set.of(ConditionParams.THRESHOLD), eqTypes);
        put(m, ConditionTypes.NEQ,          "不等于",     Set.of(ConditionParams.THRESHOLD), eqTypes);
        put(m, ConditionTypes.GT,           "大于",       Set.of(ConditionParams.THRESHOLD), numeric);
        put(m, ConditionTypes.GTE,          "大于等于",   Set.of(ConditionParams.THRESHOLD), numeric);
        put(m, ConditionTypes.LT,           "小于",       Set.of(ConditionParams.THRESHOLD), numeric);
        put(m, ConditionTypes.LTE,          "小于等于",   Set.of(ConditionParams.THRESHOLD), numeric);
        put(m, ConditionTypes.BETWEEN,      "区间内",     Set.of(ConditionParams.MIN, ConditionParams.MAX), numericDate);
        put(m, ConditionTypes.NOT_BETWEEN,  "区间外",     Set.of(ConditionParams.MIN, ConditionParams.MAX), numericDate);
        put(m, ConditionTypes.IN,           "属于集合",   Set.of(ConditionParams.VALUES), inTypes);
        put(m, ConditionTypes.NOT_IN,       "不属于集合", Set.of(ConditionParams.VALUES), inTypes);
        put(m, ConditionTypes.CONTAINS,     "集合包含",   Set.of(ConditionParams.ELEMENT), list);
        put(m, ConditionTypes.NOT_CONTAINS, "集合不包含", Set.of(ConditionParams.ELEMENT), list);
        put(m, ConditionTypes.STARTS_WITH,  "前缀匹配",   Set.of(ConditionParams.PREFIX), string);
        put(m, ConditionTypes.ENDS_WITH,    "后缀匹配",   Set.of(ConditionParams.SUFFIX), string);
        put(m, ConditionTypes.MATCHES,      "正则匹配",   Set.of(ConditionParams.REGEX), string);
        put(m, ConditionTypes.DATE_BEFORE,  "早于",       Set.of(ConditionParams.THRESHOLD), date);
        put(m, ConditionTypes.DATE_AFTER,   "晚于",       Set.of(ConditionParams.THRESHOLD), date);
        return Map.copyOf(m);
    }

    private static void put(Map<String, Spec> m, String code, String displayName,
                            Set<String> requiredKeys, Set<String> allowedDataTypes) {
        m.put(code, Spec.builder()
                .code(code)
                .displayName(displayName)
                .requiredParamKeys(requiredKeys)
                .allowedDataTypes(allowedDataTypes)
                .requiresMetric(true)
                .build());
    }

    /**
     * 查算子规格。
     *
     * @param conditionType 算子编码
     * @return 规格；目录缺席（SPI 自定义 / time.* 内置路径）返回 null
     */
    public static Spec spec(String conditionType) {
        return CATALOG.get(conditionType);
    }

    /** @return 全部内置算子规格（保序），供 MetadataService 暴露算子目录 */
    public static Collection<Spec> all() {
        return CATALOG.values();
    }
}
