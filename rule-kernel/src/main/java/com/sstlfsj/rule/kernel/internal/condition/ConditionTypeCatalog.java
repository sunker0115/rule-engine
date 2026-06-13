package com.sstlfsj.rule.kernel.internal.condition;

import com.sstlfsj.rule.kernel.api.annotation.ConditionType;
import com.sstlfsj.rule.kernel.api.operator.OperatorSpec;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 内置条件算子目录：从 {@link KernelEvaluators#defaults()} 中每个 evaluator 的
 * {@link ConditionType} 注解动态构建，算子契约与实现共同演进。
 *
 * <p>注解字段通过 {@link com.sstlfsj.rule.kernel.api.operator.ParamSpec} 枚举引用——
 * 具名预设比散落的数组字面量更 DRY；catalog 读取时直接使用枚举字段，零 switch/转换。
 *
 * <p>对标 Apache Calcite {@code SqlOperatorTable}：算子目录由算子注册表驱动。
 * SPI 自定义算子可通过注册 {@code @Bean OperatorSpec} 进入元数据目录。
 */
public final class ConditionTypeCatalog {

    private ConditionTypeCatalog() {}

    private static final Map<String, OperatorSpec> CATALOG = buildFromAnnotations();

    private static Map<String, OperatorSpec> buildFromAnnotations() {
        Map<String, OperatorSpec> m = new LinkedHashMap<>();
        KernelEvaluators.defaults().forEach((code, ev) -> {
            ConditionType ann = ev.getClass().getAnnotation(ConditionType.class);
            if (ann != null) {
                m.put(ann.value(), OperatorSpec.builder()
                        .code(ann.value())
                        .displayName(ann.displayName().isBlank() ? ann.value() : ann.displayName())
                        .requiredParamKeys(ann.schema().requiredParamKeys)
                        .allowedDataTypes(ann.schema().allowedDataTypes)
                        .requiresMetric(ann.schema().requiresMetric)
                        .build());
            }
        });
        return Map.copyOf(m);
    }

    /**
     * 查算子规格；目录缺席的 conditionType（SPI 自定义 / 未标注注解）返回 null（放行）。
     *
     * @param conditionType 算子编码
     * @return 规格；null = 缺席放行
     */
    public static OperatorSpec spec(String conditionType) {
        return CATALOG.get(conditionType);
    }

    /** @return 全部已注册算子规格（保序），供 MetadataService 暴露算子目录 */
    public static Collection<OperatorSpec> all() {
        return CATALOG.values();
    }
}
