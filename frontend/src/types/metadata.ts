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
  /** 字段名 → 声明类型（integer/number/string/boolean 等） */
  payloadFieldTypes: Record<string, string>;
  /** 后端支持的表达式引擎 lang 列表 */
  expressionLangs: string[];
}

export interface InputFieldItem {
  name: string;
  dataType: 'DECIMAL' | 'LONG' | 'STRING' | 'BOOLEAN';
  required: boolean;
}

export interface InputManifest {
  fields: InputFieldItem[];
}
