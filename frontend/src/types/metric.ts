import type { LineageRuleRef } from './decision';

export type SourceType = 'ATTRIBUTE' | 'SQL_AGGREGATE' | 'EXTERNAL_HTTP' | 'STREAM';
export type MetricDataType = 'LONG' | 'DOUBLE' | 'STRING' | 'BOOLEAN' | 'LIST' | 'DATE' | 'DATETIME';

export interface MetricDescriptor {
  metricCode: string;
  metricVersion: number;
  name: string;
  sourceType: SourceType;
  dataType: MetricDataType;
  allowProvided: boolean;
  cacheTtlSeconds: number;
  params?: Record<string, unknown>;
  status?: string;
  createdAt?: string;
  updatedAt?: string;
  tenantId?: number;
}

export interface MetricImpactResult {
  metricCode: string;
  metricVersion: number;
  affectedRules: AffectedRule[];
  affectedRuleCount: number;
}

/** 血缘：引用某 metric 的规则来源（版本无关，对称 DecisionSources） */
export interface MetricSources {
  metricCode: string;
  sources: LineageRuleRef[];
  sourceCount: number;
}

export interface AffectedRule {
  ruleDefinitionId: number;
  ruleCode: string;
  ruleName: string;
  sceneCode: string;
  status: string;
}
