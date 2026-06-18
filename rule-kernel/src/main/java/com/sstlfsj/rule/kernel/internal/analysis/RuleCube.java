package com.sstlfsj.rule.kernel.internal.analysis;

import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 把一条规则（或决策表行）投影到「每维度一个取值空间」的立方体表示，供规则集静态分析使用。
 *
 * <p>维度键由 {@code metricCode + "@" + valueRef} 构成——valueRef（METRIC / PAYLOAD）参与键，
 * 使不同取值来源视为不同维度，避免把 PAYLOAD 字段与同名 METRIC 误并到一维。
 *
 * <p>未约束（缺失）的维度视为全集 {@link ConditionSpace#any()}：缺维度 = 无约束。
 *
 * @param dims 维度键 → 该维度取值空间；某维度若由多条同维条件 meet 得来，已是它们的交集
 */
public record RuleCube(Map<String, ConditionSpace> dims) {

    public RuleCube {
        // 保插入序（CubeProjector 按 AST 子节点顺序建维）且不可变：firstEmptyDim 恒返回 AST 中靠前的矛盾维，分析报告可复现可 diff
        dims = Collections.unmodifiableMap(new LinkedHashMap<>(dims));
    }

    /** 由叶子条件计算其所属维度键：{@code metricCode + "@" + valueRef}。 */
    public static String dimKey(ConditionNode node) {
        return node.metricCode() + "@" + node.valueRef().name();
    }

    /** 该维度的取值空间；维度缺失（未约束）时返回 {@link ConditionSpace#any()}。 */
    public ConditionSpace dim(String key) {
        return dims.getOrDefault(key, ConditionSpace.any());
    }

    /** 是否存在矛盾：任一维度的取值空间为空集（如 age>30 ∧ age<10 交集为空，规则永不命中）。 */
    public boolean isIncoherent() {
        return dims.values().stream().anyMatch(ConditionSpace::isEmpty);
    }

    /** 第一个为空集的维度键（用作矛盾原因描述）；无空维度时为空。 */
    public Optional<String> firstEmptyDim() {
        return dims.entrySet().stream()
                .filter(e -> e.getValue().isEmpty())
                .map(Map.Entry::getKey)
                .findFirst();
    }
}
