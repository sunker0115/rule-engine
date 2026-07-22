package com.sstlfsj.rule.kernel.internal.analysis;

import com.sstlfsj.rule.kernel.api.analysis.FlowDeadNodeFinding;
import com.sstlfsj.rule.kernel.api.analysis.Severity;
import com.sstlfsj.rule.kernel.api.model.RuleKind;
import com.sstlfsj.rule.kernel.api.model.flow.FlowEdge;
import com.sstlfsj.rule.kernel.api.model.flow.FlowGraph;
import com.sstlfsj.rule.kernel.api.model.flow.FlowNode;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * DECISION_FLOW 决策图死节点检测：从 inputNodeId 沿 edges BFS，收集可达节点，nodes 中未被触达者即死节点。
 *
 * <p>死节点比结构校验的「孤儿节点」更强：孤儿指无任何边相连，死节点还包含「有边相连但整块从入口走不到」
 * 的分量（如互连却与入口断开的节点群）。死节点只是冗余、不影响正确性，故为 WARN。
 */
public final class FlowReachabilityDetector {

    private FlowReachabilityDetector() {}

    /**
     * 检测规则集中每条 DECISION_FLOW 规则决策图内从入口不可达的死节点。
     *
     * @param rules 待分析规则列表（可空/空）；非 flow 或 flowGraph 为 null 者跳过
     * @return 死节点发现列表，按 (ruleCode, version, deadNodeId) 升序确定性排列；无死节点时为空
     */
    public static List<FlowDeadNodeFinding> detect(List<AnalyzableRule> rules) {
        if (rules == null || rules.isEmpty()) {
            return List.of();
        }
        List<FlowDeadNodeFinding> findings = new ArrayList<>();
        for (AnalyzableRule rule : rules) {
            if (!isFlow(rule)) {
                continue;
            }
            FlowGraph flow = rule.flowGraph();
            for (String deadId : deadNodes(flow)) {
                String reason = "DECISION_FLOW 决策图节点 " + deadId
                        + " 从入口 " + flow.inputNodeId() + " 不可达（死节点）";
                findings.add(new FlowDeadNodeFinding(
                        rule.ruleCode(), rule.version(), deadId, reason, Severity.WARN));
            }
        }
        findings.sort(Comparator.comparing(FlowDeadNodeFinding::ruleCode)
                .thenComparingLong(FlowDeadNodeFinding::version)
                .thenComparing(FlowDeadNodeFinding::deadNodeId));
        return findings;
    }

    /**
     * 计算单个决策图内从 inputNodeId 不可达的死节点 id（按 id 升序）。
     *
     * @param flow 决策图（可空/空节点返回空）
     * @return 死节点 id 列表（升序）；全可达时为空
     */
    public static List<String> deadNodes(FlowGraph flow) {
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
            if (adj.containsKey(e.from()) && nodeIds.contains(e.to())) {
                adj.get(e.from()).add(e.to());
            }
        }
        Set<String> reachable = new HashSet<>();
        String input = flow.inputNodeId();
        // 入口缺失/不存在时 reachable 为空 → 全节点判死（畸形草稿分析路径可能遇到；发布期结构校验已保证入口存在）
        if (input != null && nodeIds.contains(input)) {
            Deque<String> queue = new ArrayDeque<>();
            queue.add(input);
            reachable.add(input);
            while (!queue.isEmpty()) {
                for (String w : adj.get(queue.poll())) {
                    if (reachable.add(w)) {
                        queue.add(w);
                    }
                }
            }
        }
        List<String> dead = new ArrayList<>();
        for (FlowNode n : flow.nodes()) {
            if (!reachable.contains(n.id())) {
                dead.add(n.id());
            }
        }
        dead.sort(Comparator.naturalOrder());
        return dead;
    }

    private static boolean isFlow(AnalyzableRule rule) {
        return RuleKind.DECISION_FLOW.tag().equals(rule.kind()) && rule.flowGraph() != null;
    }
}
