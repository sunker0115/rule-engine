package com.sstlfsj.rule.kernel.api.model;

import com.sstlfsj.rule.kernel.api.model.flow.FlowGraph;

import java.util.Map;

/**
 * DECISION_FLOW 规则载体：决策图 + 发布期冻结的被引规则快照。
 *
 * @param flowGraph           决策图 DAG
 * @param referencedSnapshots 发布期冻结的被引规则快照（ruleCode → 冻结 snapshot），评估期直读，守零额外查询
 */
public record FlowBody(FlowGraph flowGraph, Map<String, RuleVersionSnapshot> referencedSnapshots)
        implements RuleBody {
    public FlowBody {
        referencedSnapshots = referencedSnapshots == null ? Map.of() : Map.copyOf(referencedSnapshots);
    }
}
