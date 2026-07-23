package com.sstlfsj.rule.kernel.api.model.flow;

import java.util.List;

/**
 * 决策图：节点 + 有向边 + 入口。图只做编排，叶子逻辑由 {@link RuleRefNode} 引用的独立规则承载。
 * 与 conditionAst / scriptSource 平级，是 DECISION_FLOW 规则的 body（三承载按 kind 三选一）。
 *
 * @param nodes       全部节点（各带唯一 id）
 * @param edges       有向边（Switch 出边带 caseKey）
 * @param inputNodeId 入口节点 id
 */
public record FlowGraph(List<FlowNode> nodes, List<FlowEdge> edges, String inputNodeId) {

    public FlowGraph {
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
        edges = edges == null ? List.of() : List.copyOf(edges);
    }
}
