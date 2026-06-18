package com.sstlfsj.rule.kernel.internal.analysis;

import com.sstlfsj.rule.kernel.api.model.RuleKind;
import com.sstlfsj.rule.kernel.api.model.ast.AndNode;
import com.sstlfsj.rule.kernel.api.model.ast.AstNode;
import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 把可分析规则投影为 {@link RuleCube}。
 *
 * <p><b>仅支持「顶层 AND-of-Condition」形态</b>：根节点是单个 {@link ConditionNode}，
 * 或根是 {@link AndNode} 且其子节点全为 {@link ConditionNode}（一层扁平 AND）。
 * 其余形态（OR/XOR/NOT/IF、决策表 / 评分卡、嵌套 AND，或非 {@code AST_BOOLEAN} 种类）
 * 一律视为不可投影，返回 {@link Optional#empty()}，由编排层标记为不可分析。
 *
 * <p>同一维度上的多条条件按 {@link ConditionSpace#meet(ConditionSpace)} 取交集合并。
 * 某条件降级为 {@code unknown} 不影响投影——unknown 空间保留在其维度，由后续两两比较保守降级。
 */
public final class CubeProjector {

    private CubeProjector() {}

    /**
     * 投影一条规则为立方体。
     *
     * @param rule 待分析规则（含 AST 与 kind 标签）
     * @return 顶层 AND-of-Condition 形态的 {@link RuleCube}；其余形态为 {@link Optional#empty()}
     */
    public static Optional<RuleCube> project(AnalyzableRule rule) {
        if (!RuleKind.AST_BOOLEAN.tag().equals(rule.kind())) {
            return Optional.empty();
        }
        AstNode root = rule.ast();
        Map<String, ConditionSpace> dims = new LinkedHashMap<>();
        switch (root) {
            case ConditionNode c -> putLeaf(dims, c);
            case AndNode and -> {
                for (AstNode child : and.children()) {
                    // 仅接受一层扁平 AND：任一子节点非叶子条件即不可投影
                    if (!(child instanceof ConditionNode c)) {
                        return Optional.empty();
                    }
                    putLeaf(dims, c);
                }
            }
            default -> {
                return Optional.empty();
            }
        }
        return Optional.of(new RuleCube(dims));
    }

    /** 把一条叶子条件 meet 进它所属维度。 */
    private static void putLeaf(Map<String, ConditionSpace> dims, ConditionNode node) {
        dims.merge(RuleCube.dimKey(node), ConditionSpaceFactory.from(node), ConditionSpace::meet);
    }
}
