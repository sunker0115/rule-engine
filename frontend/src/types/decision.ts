export interface DecisionItem {
  tenantId?: number;
  code: string;
  name: string;
  priority: number;
  description?: string;
  status?: string;
  createdAt?: string;
  updatedAt?: string;
}

/** 血缘：产出 / 引用某资源的规则引用 */
export interface LineageRuleRef {
  ruleDefinitionId: number;
  ruleCode: string;
  ruleName: string;
  sceneCode: string;
  status: string;
}

/** 血缘：某 Decision 的产出来源（哪些规则会产出它） */
export interface DecisionSources {
  decisionCode: string;
  sources: LineageRuleRef[];
  sourceCount: number;
}

/** 血缘：资源被引用计数（decision / metric 通用） */
export interface UsageCount {
  code: string;
  count: number;
}
