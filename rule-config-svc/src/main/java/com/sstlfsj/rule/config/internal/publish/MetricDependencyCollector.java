package com.sstlfsj.rule.config.internal.publish;

import com.sstlfsj.rule.kernel.api.model.ast.*;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 静态扫描 AST 树，收集所有叶子 ConditionNode 引用的 metricCode（去重，保序）。 */
class MetricDependencyCollector {

    static List<String> collect(AstNode node) {
        Set<String> result = new LinkedHashSet<>();
        walk(node, result);
        return new ArrayList<>(result);
    }

    private static void walk(AstNode node, Set<String> acc) {
        switch (node) {
            case AndNode and -> and.children().forEach(c -> walk(c, acc));
            case OrNode or   -> or.children().forEach(c -> walk(c, acc));
            case NotNode not -> walk(not.child(), acc);
            case ConditionNode cond -> {
                if (cond.metricCode() != null) acc.add(cond.metricCode());
            }
            // ScorecardRootNode：直接遍历叶子条件，收集其 metricCode
            case ScorecardRootNode sc -> sc.conditions().forEach(c -> {
                if (c.metricCode() != null) acc.add(c.metricCode());
            });
            // XorNode：遍历全部子节点（全量，不短路）
            case XorNode xor -> xor.children().forEach(c -> walk(c, acc));
            // IfNode：遍历条件 + 两个分支
            case IfNode ifn -> {
                walk(ifn.condition(), acc);
                walk(ifn.thenBranch(), acc);
                if (ifn.elseBranch() != null) walk(ifn.elseBranch(), acc);
            }
            // DecisionLeafNode：终止节点，无 metric 依赖
            case DecisionLeafNode ignored -> {}
            // DecisionTableNode：遍历列头中的 metricCode
            case DecisionTableNode dt ->
                    dt.columns().forEach(col -> { if (col.metricCode() != null) acc.add(col.metricCode()); });
        }
    }
}
