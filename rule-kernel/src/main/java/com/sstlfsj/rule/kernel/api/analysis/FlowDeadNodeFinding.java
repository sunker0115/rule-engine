package com.sstlfsj.rule.kernel.api.analysis;

/**
 * DECISION_FLOW 决策图内的死节点：存在于 nodes 中但从 inputNodeId 沿 edges 不可达。死节点不影响求值正确性
 * （运行期走不到），仅是冗余配置，故 severity=WARN、只告警不拒发布。
 *
 * @param ruleCode   所属规则逻辑编码
 * @param version    规则版本
 * @param deadNodeId 不可达节点 id
 * @param reason     人类可读原因
 * @param severity   严重度（恒 WARN）
 */
public record FlowDeadNodeFinding(String ruleCode, long version, String deadNodeId,
                                  String reason, Severity severity) {
}
