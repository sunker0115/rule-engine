import type { MetricDescriptor } from './metric';

export interface ConditionTypeMeta {
  code: string;
  displayName: string;
  /** 必填参数键名列表（来自 OperatorSpec.requiredParamKeys） */
  requiredParamKeys: string[];
  /** 允许的 metric/payload dataType 标签（来自 OperatorSpec.allowedDataTypes） */
  allowedDataTypes: string[];
  /** 是否需绑定 metric/payload */
  requiresMetric: boolean;
}

export interface SceneMetadata {
  conditionTypes: ConditionTypeMeta[];
  availableMetrics: MetricDescriptor[];
  eventTypes: string[];
  /** 场景 payloadSchema 中声明的字段名 */
  payloadFieldNames: string[];
}

export interface InputFieldItem {
  name: string;
  dataType: 'DECIMAL' | 'LONG' | 'STRING' | 'BOOLEAN';
  required: boolean;
}

export interface InputManifest {
  fields: InputFieldItem[];
}
