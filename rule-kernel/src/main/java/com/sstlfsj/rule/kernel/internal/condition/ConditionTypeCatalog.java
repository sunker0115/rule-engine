package com.sstlfsj.rule.kernel.internal.condition;

import com.sstlfsj.rule.kernel.api.operator.OperatorSpec;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 内置条件算子目录：从 {@link KernelEvaluators#defaults()} 中每个 evaluator 的
 * {@link com.sstlfsj.rule.kernel.api.spi.condition.ConditionEvaluator#spec()} 动态构建，
 * 消除与 evaluator 实现的双重声明。算子契约与实现共同演进，不再需要外部维护硬编码列表。
 *
 * <p>对标 Apache Calcite {@code SqlOperatorTable}：算子目录由算子注册表驱动。
 * SPI 自定义算子 override {@code spec()} 即可获得发布期校验 + 元数据暴露。
 */
public final class ConditionTypeCatalog {

    private ConditionTypeCatalog() {}

    private static final Map<String, OperatorSpec> CATALOG = buildFromEvaluators();

    private static Map<String, OperatorSpec> buildFromEvaluators() {
        Map<String, OperatorSpec> m = new LinkedHashMap<>();
        KernelEvaluators.defaults().forEach((code, ev) ->
                ev.spec().ifPresent(spec -> m.put(code, spec)));
        return Map.copyOf(m);
    }

    /**
     * 查算子规格；目录缺席的 conditionType（SPI 自定义 / 未声明 spec）返回 null（放行）。
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
