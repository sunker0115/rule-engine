package com.sstlfsj.rule.kernel.internal.analysis;

import com.sstlfsj.rule.kernel.api.analysis.RedundancyFinding;
import com.sstlfsj.rule.kernel.api.analysis.Severity;
import com.sstlfsj.rule.kernel.api.model.ConditionParams;
import com.sstlfsj.rule.kernel.api.model.RuleKind;
import com.sstlfsj.rule.kernel.api.model.ast.AndNode;
import com.sstlfsj.rule.kernel.api.model.ast.AstNode;
import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;
import com.sstlfsj.rule.kernel.api.model.ast.IfNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 单规则内冗余条件检测：在同一条规则的某个 AND-of-condition 组里，若条件 X 被同组另一条件 Y 蕴含
 * （Y 的取值空间 ⊆ X 的取值空间，Y 更严格），则 X 冗余、可删除。
 *
 * <p>组的来源：
 * <ul>
 *   <li>AST_BOOLEAN：根为 {@link AndNode} 且其 children 全为 {@link ConditionNode} → 即一个组
 *       （单 ConditionNode 根无组；含 OR/NOT/嵌套则跳过，不在本 detector 职责内）。</li>
 *   <li>DECISION_TREE：递归遍历 AST，对每个 {@link IfNode}，若其 condition 为「全 ConditionNode 的 AndNode」→ 一个组；
 *       continue 遍历 thenBranch / elseBranch 到达嵌套 IfNode。</li>
 *   <li>其余 kind（DECISION_TABLE / SCORECARD / EXPRESSION_SCRIPT）→ 无组，跳过。</li>
 * </ul>
 *
 * <p>组内判定（基于 {@link ConditionSpaceFactory} + {@link ConditionSpace#subsumes}，复用同一区间数学，零重复）：
 * 对同维（{@link RuleCube#dimKey} 相等）的有序对 (X, Y)，X≠Y，若 {@code from(X).subsumes(from(Y)) == TRUE}
 * 则 X 被 Y 蕴含 → X 冗余。精确重复（互相 subsumes）按确定性规则报告靠后者为冗余。任一为 UNKNOWN 跳过（零误报）。
 * 每个冗余条件在一组内至多报告一次（找到首个蕴含它的伙伴即停）。输出按 (ruleCode, redundantCondition) 升序确定性排列。
 */
public final class RedundancyDetector {

    private RedundancyDetector() {}

    /**
     * 检测规则集中每条规则内部的冗余条件。
     *
     * @param rules 待检测规则列表；{@code null} / 空时返回空列表
     * @return 冗余发现，按 (ruleCode, redundantCondition) 升序确定性排列；永不为 null
     */
    public static List<RedundancyFinding> detect(List<AnalyzableRule> rules) {
        List<RedundancyFinding> findings = new ArrayList<>();
        if (rules == null || rules.isEmpty()) {
            return findings;
        }
        for (AnalyzableRule rule : rules) {
            for (List<ConditionNode> group : collectGroups(rule)) {
                analyzeGroup(rule.ruleCode(), group, findings);
            }
        }
        findings.sort(Comparator.comparing(RedundancyFinding::ruleCode)
                .thenComparing(RedundancyFinding::redundantCondition));
        return findings;
    }

    /** 按规则种类收集其全部 AND-of-condition 组。 */
    private static List<List<ConditionNode>> collectGroups(AnalyzableRule rule) {
        List<List<ConditionNode>> groups = new ArrayList<>();
        if (RuleKind.AST_BOOLEAN.tag().equals(rule.kind())) {
            asConditionGroup(rule.ast()).ifPresent(groups::add);
        } else if (RuleKind.DECISION_TREE.tag().equals(rule.kind())) {
            collectTreeGroups(rule.ast(), groups);
        }
        return groups;
    }

    /** 递归遍历决策树：每个 IfNode 的 condition 若为全 ConditionNode 的 AndNode 即一个组，并下钻 then/else 分支。 */
    private static void collectTreeGroups(AstNode node, List<List<ConditionNode>> groups) {
        if (!(node instanceof IfNode ifNode)) {
            return;
        }
        asConditionGroup(ifNode.condition()).ifPresent(groups::add);
        collectTreeGroups(ifNode.thenBranch(), groups);
        collectTreeGroups(ifNode.elseBranch(), groups);
    }

    /** 当且仅当 node 为子节点全是 {@link ConditionNode} 的 {@link AndNode} 时，返回其条件列表作为一个组。 */
    private static java.util.Optional<List<ConditionNode>> asConditionGroup(AstNode node) {
        if (!(node instanceof AndNode and)) {
            return java.util.Optional.empty();
        }
        List<ConditionNode> conditions = new ArrayList<>(and.children().size());
        for (AstNode child : and.children()) {
            if (!(child instanceof ConditionNode c)) {
                return java.util.Optional.empty(); // 含非叶子（OR/嵌套）→ 非本 detector 处理的扁平组
            }
            conditions.add(c);
        }
        return java.util.Optional.of(conditions);
    }

    /**
     * 组内冗余判定：对每个条件 X，找首个同维、蕴含 X 的伙伴 Y（X≠Y）。
     * 精确重复（互相 subsumes）时仅靠后者算冗余——X 仅与下标更小的 Y 配对成立，靠前者不会被靠后者标冗余。
     */
    private static void analyzeGroup(String ruleCode, List<ConditionNode> group, List<RedundancyFinding> out) {
        for (int xi = 0; xi < group.size(); xi++) {
            ConditionNode x = group.get(xi);
            ConditionSpace spaceX = ConditionSpaceFactory.from(x);
            String dimX = RuleCube.dimKey(x);
            for (int yi = 0; yi < group.size(); yi++) {
                if (xi == yi) {
                    continue;
                }
                ConditionNode y = group.get(yi);
                if (!dimX.equals(RuleCube.dimKey(y))) {
                    continue;
                }
                ConditionSpace spaceY = ConditionSpaceFactory.from(y);
                if (spaceX.subsumes(spaceY) != Tri.TRUE) {
                    continue;
                }
                // X ⊇ Y：Y 更严格、蕴含 X。若同时 Y ⊇ X（精确重复），仅当 X 下标更大时算冗余，避免双报。
                boolean equalSpaces = spaceY.subsumes(spaceX) == Tri.TRUE;
                if (equalSpaces && xi < yi) {
                    continue;
                }
                out.add(new RedundancyFinding(ruleCode, describe(x), describe(y),
                        describe(x) + " 被同组 " + describe(y) + " 蕴含，可删除", Severity.INFO));
                break; // 每个冗余条件至多报告一次
            }
        }
    }

    /** 条件的人类可读描述：{@code metricCode 算子 关键参数值}（如 {@code amount LTE 10}），缺参数时省略值。 */
    private static String describe(ConditionNode node) {
        Object threshold = node.params().get(ConditionParams.THRESHOLD);
        Object values = node.params().get(ConditionParams.VALUES);
        String key = threshold != null ? String.valueOf(threshold)
                : values != null ? String.valueOf(values) : "";
        String base = node.metricCode() + " " + node.conditionType();
        return key.isEmpty() ? base : base + " " + key;
    }
}
