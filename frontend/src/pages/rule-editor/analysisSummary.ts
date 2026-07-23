import type { RuleKind, RuleSetAnalysisReport, Severity, FlowCycleFinding, FlowDeadNodeFinding } from '@/types';

/**
 * 可触发规则集分析拉取的种类。合取语义（AST_BOOLEAN / DECISION_TREE / DECISION_TABLE）走跨规则推理；
 * DECISION_FLOW 走图内分析（环 / 死节点），结果分流给画布可视化，不进跨规则面板。
 * SCORECARD / EXPRESSION_SCRIPT 不可静态分析。
 */
export const ANALYZABLE_KINDS: readonly RuleKind[] = ['AST_BOOLEAN', 'DECISION_TREE', 'DECISION_TABLE', 'DECISION_FLOW'];

/** 当前规则种类是否支持规则集静态分析。 */
export function isAnalyzableKind(kind: RuleKind): boolean {
  return ANALYZABLE_KINDS.includes(kind);
}

/** loc 形如 "R1" 或 "R1#row2"——取 # 前规则码部分。 */
function ruleCodeOf(loc: string): string {
  const i = loc.indexOf('#');
  return i >= 0 ? loc.slice(0, i) : loc;
}

/** 摘要计数：各严重度的告警总数 + 未分析数 + 是否存在任意 finding。 */
export interface AnalysisSummary {
  error: number;
  warn: number;
  info: number;
  unanalyzable: number;
  /** 摘要条/badge 的"未读"计数：真正的问题数（不含未分析）。 */
  findingCount: number;
}

/** 由报告聚合 scene 级摘要计数。 */
export function summarize(report: RuleSetAnalysisReport): AnalysisSummary {
  const bySev = (s: Severity) =>
    report.incoherences.filter((x) => x.severity === s).length +
    report.deadRules.filter((x) => x.severity === s).length +
    report.conflicts.filter((x) => x.severity === s).length +
    report.overlaps.filter((x) => x.severity === s).length +
    report.coverageGaps.filter((x) => x.severity === s).length +
    report.redundancies.filter((x) => x.severity === s).length;
  const error = bySev('ERROR');
  const warn = bySev('WARN');
  const info = bySev('INFO');
  return {
    error,
    warn,
    info,
    unanalyzable: report.unanalyzableRules.length,
    findingCount: error + warn + info,
  };
}

const SEV_RANK: Record<Severity, number> = { ERROR: 3, WARN: 2, INFO: 1 };

/**
 * 某规则码在报告里出现过的最坏严重度；'NA' 表示仅出现在未分析列表；null 表示未涉及。
 * 用于左栏当前规则的 badge。
 */
export function worstSeverityForRule(report: RuleSetAnalysisReport, ruleCode: string): Severity | 'NA' | null {
  let worst: Severity | null = null;
  const bump = (s: Severity) => {
    if (!worst || SEV_RANK[s] > SEV_RANK[worst]) worst = s;
  };
  report.incoherences.forEach((x) => x.ruleCode === ruleCode && bump(x.severity));
  report.deadRules.forEach((x) => x.deadRuleCode === ruleCode && bump(x.severity));
  report.conflicts.forEach((x) => (ruleCodeOf(x.locA) === ruleCode || ruleCodeOf(x.locB) === ruleCode) && bump(x.severity));
  report.overlaps.forEach((x) => (ruleCodeOf(x.locA) === ruleCode || ruleCodeOf(x.locB) === ruleCode) && bump(x.severity));
  report.redundancies.forEach((x) => x.ruleCode === ruleCode && bump(x.severity));
  if (worst) return worst;
  if (report.unanalyzableRules.some((x) => x.ruleCode === ruleCode)) return 'NA';
  return null;
}

/** 某 DECISION_FLOW 规则图内的环 finding（供画布把成环边标红）。跨规则维度不含此项。 */
export function flowCyclesForRule(report: RuleSetAnalysisReport, ruleCode: string): FlowCycleFinding[] {
  return (report.flowCycles ?? []).filter((x) => x.ruleCode === ruleCode);
}

/** 某 DECISION_FLOW 规则图内的死节点 finding（供画布把死节点置灰）。跨规则维度不含此项。 */
export function flowDeadNodesForRule(report: RuleSetAnalysisReport, ruleCode: string): FlowDeadNodeFinding[] {
  return (report.flowDeadNodes ?? []).filter((x) => x.ruleCode === ruleCode);
}
