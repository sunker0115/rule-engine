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
