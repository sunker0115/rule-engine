/** 规则集静态分析告警的严重级别。 */
export type Severity = 'INFO' | 'WARN' | 'ERROR';

/** 条件自相矛盾——规则永不命中。 */
export interface Incoherence {
  ruleCode: string;
  reason: string;
  severity: Severity;
}

/** 死规则——被更高优先级规则完全遮蔽，决策永不胜出。 */
export interface DeadRule {
  deadRuleCode: string;
  coveredByRuleCode: string;
  reason: string;
  severity: Severity;
}

/** 冲突——两处输入相交但决策对立。loc 为 ruleCode（如 "R1"）或决策表行（如 "R1#row2"）。 */
export interface Conflict {
  locA: string;
  locB: string;
  decisionA: string;
  decisionB: string;
  reason: string;
  severity: Severity;
}

/** 重叠——两处输入相交但决策一致，提示可合并而非错误。 */
export interface Overlap {
  locA: string;
  locB: string;
  reason: string;
  severity: Severity;
}

/** 覆盖缺口——绑定了 decision 但无规则路径产出它。 */
export interface CoverageGap {
  decisionCode: string;
  reason: string;
  severity: Severity;
}

/** 未分析规则——超出 v1 精确推理能力被跳过（灰显，非"无问题"）。 */
export interface UnanalyzableRule {
  ruleCode: string;
  reason: string;
}

/** 冗余条件——规则内某条件被另一条件蕴含，恒为冗余（严重度恒 INFO）。 */
export interface RedundancyFinding {
  ruleCode: string;
  redundantCondition: string;
  impliedByCondition: string;
  reason: string;
  severity: Severity;
}

/** DECISION_FLOW 决策图内的有向环——发布期拒收（严重度恒 ERROR）。 */
export interface FlowCycleFinding {
  ruleCode: string;
  version: number;
  cycleNodeIds: string[];
  reason: string;
  severity: Severity;
}

/** DECISION_FLOW 决策图内从入口不可达的死节点——仅告警（严重度恒 WARN）。 */
export interface FlowDeadNodeFinding {
  ruleCode: string;
  version: number;
  deadNodeId: string;
  reason: string;
  severity: Severity;
}

/** 规则集静态分析报告——对齐 GET /admin/v1/scenes/{sceneCode}/analysis 响应。 */
export interface RuleSetAnalysisReport {
  sceneCode: string;
  incoherences: Incoherence[];
  deadRules: DeadRule[];
  conflicts: Conflict[];
  overlaps: Overlap[];
  coverageGaps: CoverageGap[];
  unanalyzableRules: UnanalyzableRule[];
  redundancies: RedundancyFinding[];
  /** DECISION_FLOW 决策图内有向环（图内维度，供画布标红成环边）。 */
  flowCycles: FlowCycleFinding[];
  /** DECISION_FLOW 决策图内死节点（图内维度，供画布置灰死节点）。 */
  flowDeadNodes: FlowDeadNodeFinding[];
}
