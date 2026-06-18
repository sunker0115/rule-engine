export type ImportPolicy = 'SKIP' | 'OVERWRITE' | 'ABORT';

/** Bundle v2 diff 报告（dry-run 和 apply 均返回）。 */
export interface ImportDiffReport {
  willCreate: RuleImportItem[];
  willOverwrite: RuleImportItem[];
  skipped: RuleImportItem[];
  conflicts: RuleImportConflict[];
  scenesCreated: number;
  metricsCreated: number;
  decisionsCreated: number;
}

export interface RuleImportItem {
  ruleCode: string;
  sceneCode: string;
  reason: string;
}

export interface RuleImportConflict {
  ruleCode: string;
  sceneCode: string;
  conflictType: string;
  detail: string;
}

/** v1 兼容（旧 API，保留但不再使用）*/
export interface RuleImportResult {
  rules: ImportedRule[];
  scenesCreated: string[];
  scenesSkippedExisting: string[];
  metricsCreated: string[];
  metricsSkippedExisting: string[];
  metricsRequiringReview: string[];
  decisionsCreated: string[];
  decisionsSkippedExisting: string[];
}

export interface ImportedRule {
  ruleDefinitionId: number;
  ruleVersionId: number;
  version: number;
  code: string;
  sceneCode: string;
  ruleAlreadyExisted: boolean;
}
