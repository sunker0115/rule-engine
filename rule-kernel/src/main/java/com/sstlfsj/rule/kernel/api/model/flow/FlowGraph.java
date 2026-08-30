package com.sstlfsj.rule.kernel.api.model.flow;

import java.util.List;
import java.util.Map;

/**
 * 决策图：节点 + 有向边 + 入口 + 冻结常量。
 * 图只做编排，叶子逻辑由 {@link RuleRefNode} 引用的独立规则承载。
 * 与 conditionAst / scriptSource 平级，是 DECISION_FLOW 规则的 body（三承载按 kind 三选一）。
 *
 * @param nodes       全部节点（各带唯一 id）
 * @param edges       有向边（Switch 出边带 caseKey）
 * @param inputNodeId 入口节点 id
 * @param params      冻结常量命名空间（求值期并入 binding 的 {@code params} key），缺省空 map
 */
public record FlowGraph(
        List<FlowNode> nodes,
        List<FlowEdge> edges,
        String inputNodeId,
        Map<String, Object> params) {

    public FlowGraph {
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
        edges = edges == null ? List.of() : List.copyOf(edges);
        params = params == null ? Map.of() : Map.copyOf(params);
    }

    /** 向后兼容：无冻结常量的 flow 图。 */
    public FlowGraph(List<FlowNode> nodes, List<FlowEdge> edges, String inputNodeId) {
        this(nodes, edges, inputNodeId, Map.of());
    }
}
