package com.sstlfsj.rule.config.internal.publish;

import com.sstlfsj.rule.kernel.api.model.ValueRef;
import com.sstlfsj.rule.kernel.api.model.ast.*;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 静态扫描 AST 树，收集所有 valueRef=PAYLOAD 的 ConditionNode 引用的字段名（即 metricCode，去重，保序）。 */
class PayloadFieldCollector {

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
                // 仅收集 payload 引用字段（MetricDependencyCollector 的逆，受治理 metric 不计入）
                if (cond.valueRef() == ValueRef.PAYLOAD && cond.metricCode() != null) acc.add(cond.metricCode());
            }
            // ScorecardRootNode：直接遍历叶子条件，收集 payload 字段
            case ScorecardRootNode sc -> sc.conditions().forEach(c -> {
                if (c.valueRef() == ValueRef.PAYLOAD && c.metricCode() != null) acc.add(c.metricCode());
            });
            // XorNode：遍历全部子节点（全量，不短路）
            case XorNode xor -> xor.children().forEach(c -> walk(c, acc));
            // IfNode：遍历条件 + 两个分支
            case IfNode ifn -> {
                walk(ifn.condition(), acc);
                walk(ifn.thenBranch(), acc);
                if (ifn.elseBranch() != null) walk(ifn.elseBranch(), acc);
            }
            // DecisionLeafNode：终止节点，无 payload 引用
            case DecisionLeafNode ignored -> {}
            // DecisionTableNode：决策表列本轮不支持 payload，无 payload 引用
            case DecisionTableNode ignored -> {}
        }
    }
}
