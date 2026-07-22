package com.sstlfsj.rule.kernel.internal.analysis;

import com.sstlfsj.rule.kernel.api.analysis.CoverageGapFinding;
import com.sstlfsj.rule.kernel.api.analysis.Severity;
import com.sstlfsj.rule.kernel.api.model.RuleKind;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;
import com.sstlfsj.rule.kernel.api.model.ast.AndNode;
import com.sstlfsj.rule.kernel.api.model.ast.AstNode;
import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;
import com.sstlfsj.rule.kernel.api.model.ast.DecisionLeafNode;
import com.sstlfsj.rule.kernel.api.model.ast.DecisionTableNode;
import com.sstlfsj.rule.kernel.api.model.ast.IfNode;
import com.sstlfsj.rule.kernel.api.model.ast.NotNode;
import com.sstlfsj.rule.kernel.api.model.ast.OrNode;
import com.sstlfsj.rule.kernel.api.model.ast.ScoreBand;
import com.sstlfsj.rule.kernel.api.model.ast.ScorecardRootNode;
import com.sstlfsj.rule.kernel.api.model.ast.XorNode;
import com.sstlfsj.rule.kernel.api.model.flow.FlowGraph;
import com.sstlfsj.rule.kernel.api.model.flow.FlowNode;
import com.sstlfsj.rule.kernel.api.model.flow.OutputNode;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * 决策可达性缺口检测：找出被某规则绑定（声明）、却无任何规则路径能实际产出的 Decision。
 * 纯集合逻辑——declared（全部规则的绑定并集）减去 producible（全部规则可产出码的并集）即缺口。
 */
public final class CoverageGapDetector {

    private CoverageGapDetector() {
    }

    /**
     * 检测规则集中"绑定了但不可达"的决策。
     *
     * @param rules 待分析规则列表（可空/空）
     * @return 缺口发现列表，按 decisionCode 升序排列（确定性输出）；无缺口时为空
     */
    public static List<CoverageGapFinding> detect(List<AnalyzableRule> rules) {
        if (rules == null || rules.isEmpty()) {
            return List.of();
        }

        Set<String> declared = new LinkedHashSet<>();
        Set<String> producible = new LinkedHashSet<>();
        for (AnalyzableRule rule : rules) {
            for (RuleVersionSnapshot.DecisionBinding binding : rule.bindings()) {
                // 与 producible 两侧过滤一致：畸形 binding（null/blank）不该以 NPE/幽灵 finding 暴露
                addIfPresent(declared, binding.decisionCode());
            }
            producible.addAll(producibleCodes(rule));
        }

        // gap = declared − producible，TreeSet 保证按 decisionCode 升序确定性输出
        Set<String> gap = new TreeSet<>(declared);
        gap.removeAll(producible);

        return gap.stream()
                .map(code -> new CoverageGapFinding(
                        code,
                        "Decision " + code + " 被规则绑定但无任何规则路径可产出（不可达）",
                        Severity.WARN))
                .toList();
    }

    /** 计算单条规则按其 kind 实际可产出的决策码集合。 */
    private static Set<String> producibleCodes(AnalyzableRule rule) {
        Set<String> codes = new LinkedHashSet<>();
        RuleKind kind = parseKind(rule.kind());
        if (kind == null) {
            // 未知 kind：无法内省，保守退回绑定避免误报
            addBindings(rule, codes);
            return codes;
        }
        switch (kind) {
            case DECISION_TREE -> collectLeafDecisionCodes(rule.ast(), codes);
            case DECISION_TABLE -> {
                if (rule.ast() instanceof DecisionTableNode table) {
                    for (DecisionTableNode.Row row : table.rows()) {
                        addIfPresent(codes, row.decisionCode());
                    }
                } else {
                    addBindings(rule, codes);
                }
            }
            case SCORECARD -> {
                if (rule.ast() instanceof ScorecardRootNode card && !card.bands().isEmpty()) {
                    for (ScoreBand band : card.bands()) {
                        addIfPresent(codes, band.decisionCode());
                    }
                } else {
                    // 无分段评分卡仅按 threshold 单命中 → 退回绑定
                    addBindings(rule, codes);
                }
            }
            // DECISION_FLOW：可产出 = 全图 OutputNode.decisionCode（图定义决策面）；无 flowGraph 时退回绑定
            case DECISION_FLOW -> collectFlowOutputCodes(rule.flowGraph(), rule, codes);
            // AST_BOOLEAN：命中即产出全部绑定；EXPRESSION_SCRIPT：无法内省脚本输出，保守退回绑定避免误报
            case AST_BOOLEAN, EXPRESSION_SCRIPT -> addBindings(rule, codes);
        }
        return codes;
    }

    /** 收集 DECISION_FLOW 全图 OutputNode 的 decisionCode；无 flowGraph（畸形/缺失）时保守退回绑定。 */
    private static void collectFlowOutputCodes(FlowGraph flow, AnalyzableRule rule, Set<String> codes) {
        if (flow == null) {
            addBindings(rule, codes);
            return;
        }
        for (FlowNode node : flow.nodes()) {
            if (node instanceof OutputNode output) {
                addIfPresent(codes, output.decisionCode());
            }
        }
    }

    /** 把规则的全部绑定决策码计入可产出集（保守兜底用）。 */
    private static void addBindings(AnalyzableRule rule, Set<String> codes) {
        for (RuleVersionSnapshot.DecisionBinding binding : rule.bindings()) {
            addIfPresent(codes, binding.decisionCode());
        }
    }

    private static void addIfPresent(Set<String> codes, String code) {
        if (code != null && !code.isBlank()) {
            codes.add(code);
        }
    }

    /** 解析 kind 标签为枚举；无法识别（null/未知串）时返回 null，由上层走保守兜底分支。 */
    private static RuleKind parseKind(String kind) {
        if (kind == null) {
            return null;
        }
        try {
            return RuleKind.valueOf(kind);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * 递归遍历 DECISION_TREE 的 AST，收集所有 {@link DecisionLeafNode} 的 decisionCode。
     * 对 sealed AstNode 全覆盖：分支节点下钻其分支/子节点，叶子节点加码，条件类节点无产出。
     *
     * @param node  当前节点（可空，空则直接返回）
     * @param codes 累加目标集合
     */
    private static void collectLeafDecisionCodes(AstNode node, Set<String> codes) {
        if (node == null) {
            return;
        }
        switch (node) {
            case DecisionLeafNode leaf -> addIfPresent(codes, leaf.decisionCode());
            // IfNode.condition 是判定谓词不产出决策，仅下钻 then/else 两个分支
            case IfNode ifNode -> {
                collectLeafDecisionCodes(ifNode.thenBranch(), codes);
                collectLeafDecisionCodes(ifNode.elseBranch(), codes);
            }
            case AndNode and -> and.children().forEach(c -> collectLeafDecisionCodes(c, codes));
            case OrNode or -> or.children().forEach(c -> collectLeafDecisionCodes(c, codes));
            case XorNode xor -> xor.children().forEach(c -> collectLeafDecisionCodes(c, codes));
            case NotNode not -> collectLeafDecisionCodes(not.child(), codes);
            // 叶子条件 / 决策表 / 评分卡根：DECISION_TREE 遍历语境下无可产出决策
            case ConditionNode ignored -> {
            }
            case DecisionTableNode ignored -> {
            }
            case ScorecardRootNode ignored -> {
            }
        }
    }
}
