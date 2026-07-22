package com.sstlfsj.rule.kernel.internal.analysis;

import com.sstlfsj.rule.kernel.api.analysis.FlowCycleFinding;
import com.sstlfsj.rule.kernel.api.analysis.Severity;
import com.sstlfsj.rule.kernel.api.model.RuleKind;
import com.sstlfsj.rule.kernel.api.model.flow.FlowEdge;
import com.sstlfsj.rule.kernel.api.model.flow.FlowGraph;
import com.sstlfsj.rule.kernel.api.model.flow.FlowNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * DECISION_FLOW 决策图环检测：把单个 flow 的 FlowNode/FlowEdge 视为有向图（边 from→to），三色 DFS 找回路。
 *
 * <p>仅遍历本 flow 图内的节点邻接，<b>不</b>跨 {@link com.sstlfsj.rule.kernel.api.model.flow.RuleRefNode}
 * 展开被引规则（那是运行期投影，非图结构）。起点按节点声明顺序遍历，覆盖从入口不可达分量里的环；
 * 邻接按边声明顺序保序，保证同一输入下首个命中的环确定，输出稳定。
 */
public final class FlowCycleDetector {

    private FlowCycleDetector() {}

    /** DFS 三色标记。 */
    private enum Color { WHITE, GRAY, BLACK }

    /**
     * 检测规则集中每条 DECISION_FLOW 规则的决策图是否成环。
     *
     * @param rules 待分析规则列表（可空/空）；非 flow 或 flowGraph 为 null 者跳过
     * @return 环发现列表，按 (ruleCode, version) 升序确定性排列；无环时为空
     */
    public static List<FlowCycleFinding> detect(List<AnalyzableRule> rules) {
        if (rules == null || rules.isEmpty()) {
            return List.of();
        }
        List<FlowCycleFinding> findings = new ArrayList<>();
        for (AnalyzableRule rule : rules) {
            if (!isFlow(rule)) {
                continue;
            }
            List<String> cycle = findCycle(rule.flowGraph());
            if (!cycle.isEmpty()) {
                String reason = "DECISION_FLOW 决策图存在环: "
                        + String.join(" -> ", cycle) + " -> " + cycle.getFirst();
                findings.add(new FlowCycleFinding(
                        rule.ruleCode(), rule.version(), cycle, reason, Severity.ERROR));
            }
        }
        findings.sort(Comparator.comparing(FlowCycleFinding::ruleCode)
                .thenComparingLong(FlowCycleFinding::version));
        return findings;
    }

    /**
     * 在单个决策图内找一个有向环（供发布期前置校验直接复用）。
     *
     * @param flow 决策图（可空/空节点直接返回无环）
     * @return 构成环的节点 id 列表（首元素→…→末元素→首元素闭合）；无环时为空列表
     */
    public static List<String> findCycle(FlowGraph flow) {
        if (flow == null || flow.nodes().isEmpty()) {
            return List.of();
        }
        Set<String> nodeIds = new LinkedHashSet<>();
        Map<String, List<String>> adj = new LinkedHashMap<>();
        for (FlowNode n : flow.nodes()) {
            nodeIds.add(n.id());
            adj.putIfAbsent(n.id(), new ArrayList<>());
        }
        for (FlowEdge e : flow.edges()) {
            // 悬挂端点（结构校验已排除）防御式跳过，避免 NPE
            if (adj.containsKey(e.from()) && nodeIds.contains(e.to())) {
                adj.get(e.from()).add(e.to());
            }
        }
        Map<String, Color> color = new HashMap<>();
        for (String id : nodeIds) {
            color.put(id, Color.WHITE);
        }
        List<String> path = new ArrayList<>();
        for (FlowNode n : flow.nodes()) {
            if (color.get(n.id()) == Color.WHITE) {
                List<String> cycle = dfs(n.id(), adj, color, path);
                if (!cycle.isEmpty()) {
                    return cycle;
                }
            }
        }
        return List.of();
    }

    /** 从 v 递归 DFS；遇到灰色邻居即回边成环，回溯 path 提取环节点序列。 */
    private static List<String> dfs(String v, Map<String, List<String>> adj,
                                    Map<String, Color> color, List<String> path) {
        color.put(v, Color.GRAY);
        path.add(v);
        for (String w : adj.get(v)) {
            Color c = color.get(w);
            if (c == Color.GRAY) {
                // 回边 v→w：环 = path 从 w 首现处到末尾（w..v），v→w 闭合
                int idx = path.indexOf(w);
                return new ArrayList<>(path.subList(idx, path.size()));
            }
            if (c == Color.WHITE) {
                List<String> cycle = dfs(w, adj, color, path);
                if (!cycle.isEmpty()) {
                    return cycle;
                }
            }
        }
        color.put(v, Color.BLACK);
        path.removeLast();
        return List.of();
    }

    private static boolean isFlow(AnalyzableRule rule) {
        return RuleKind.DECISION_FLOW.tag().equals(rule.kind()) && rule.flowGraph() != null;
    }
}
