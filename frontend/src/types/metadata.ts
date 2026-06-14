import type { MetricDescriptor } from './metric';

export interface ConditionTypeMeta {
  code: string;
  displayName: string;
  paramsSchema: Record<string, unknown>;
  requiresMetric: boolean;
}

export interface SceneMetadata {
  conditionTypes: ConditionTypeMeta[];
  availableMetrics: MetricDescriptor[];
}

export interface InputFieldItem {
  name: string;
  dataType: 'DECIMAL' | 'LONG' | 'STRING' | 'BOOLEAN';
  required: boolean;
}

export interface InputManifest {
  fields: InputFieldItem[];
}
