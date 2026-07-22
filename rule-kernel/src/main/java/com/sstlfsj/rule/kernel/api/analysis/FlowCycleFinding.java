package com.sstlfsj.rule.kernel.api.analysis;

import java.util.List;

/**
 * DECISION_FLOW 决策图内存在有向环（节点沿 edges 首尾相接成回路）。环使发布期解析无法定序，运行期虽有
 * visited 兜底防死循环，仍属配置错误，故 severity=ERROR、发布期拒收。
 *
 * @param ruleCode     所属规则逻辑编码
 * @param version      规则版本
 * @param cycleNodeIds 构成环的节点 id 列表（按 DFS 遇到的顺序，首元素→…→末元素→首元素闭合）
 * @param reason       人类可读原因（含环路径）
 * @param severity     严重度（恒 ERROR）
 */
public record FlowCycleFinding(String ruleCode, long version, List<String> cycleNodeIds,
                               String reason, Severity severity) {

    public FlowCycleFinding {
        cycleNodeIds = cycleNodeIds == null ? List.of() : List.copyOf(cycleNodeIds);
    }
}
