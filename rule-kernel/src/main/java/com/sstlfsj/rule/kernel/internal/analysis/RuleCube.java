package com.sstlfsj.rule.kernel.internal.analysis;

import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

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

    /**
     * 两立方体是否存在公共点（输入区间相交）。
     *
     * <p>逐维度求 {@code this.dim(k).overlaps(other.dim(k))}，维度集 = 两立方体键的并集，
     * 缺失维度按 {@link ConditionSpace#any()} 补全。聚合采用三值合取语义：
     * 任一维 FALSE（该维不相交）⇒ 立方体必不相交，返回 FALSE（FALSE 短路优先于 UNKNOWN）；
     * 否则任一维 UNKNOWN ⇒ 无法判定，返回 UNKNOWN；全部维 TRUE ⇒ 返回 TRUE。
     *
     * @param other 另一立方体
     * @return 相交三值结果
     */
    public Tri overlaps(RuleCube other) {
        boolean sawUnknown = false;
        for (String key : unionKeys(other)) {
            Tri dimResult = dim(key).overlaps(other.dim(key));
            if (dimResult == Tri.FALSE) {
                return Tri.FALSE;
            }
            if (dimResult == Tri.UNKNOWN) {
                sawUnknown = true;
            }
        }
        return sawUnknown ? Tri.UNKNOWN : Tri.TRUE;
    }

    /**
     * 本立方体是否完全包含另一立方体（this ⊇ other：other 的每个点都落在 this 内）。
     *
     * <p>逐维度求 {@code this.dim(k).subsumes(other.dim(k))}，维度集 = 两立方体键的并集，
     * 缺失维度按 {@link ConditionSpace#any()} 补全。任一维 FALSE ⇒ 返回 FALSE（短路）；
     * 否则任一维 UNKNOWN ⇒ 返回 UNKNOWN；全部维 TRUE ⇒ 返回 TRUE。
     *
     * @param other 被包含候选立方体
     * @return 包含三值结果
     */
    public Tri subsumes(RuleCube other) {
        boolean sawUnknown = false;
        for (String key : unionKeys(other)) {
            Tri dimResult = dim(key).subsumes(other.dim(key));
            if (dimResult == Tri.FALSE) {
                return Tri.FALSE;
            }
            if (dimResult == Tri.UNKNOWN) {
                sawUnknown = true;
            }
        }
        return sawUnknown ? Tri.UNKNOWN : Tri.TRUE;
    }

    /** 两立方体维度键的并集（保插入序，确定性遍历）。 */
    private Set<String> unionKeys(RuleCube other) {
        Set<String> keys = new LinkedHashSet<>(dims.keySet());
        keys.addAll(other.dims.keySet());
        return keys;
    }
}
