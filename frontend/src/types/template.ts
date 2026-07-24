import type { RuleBody, RuleKind } from './rule';

export type TemplateStatus = 'DRAFT' | 'PUBLISHED' | 'DISABLED';

/** Slot 种类，决定实例化验证与前端 picker。 */
export type SlotKind = 'VALUE' | 'METRIC_REF' | 'DECISION_REF' | 'RULE_REF';

/** VALUE kind 的值类型（对齐后端 ValueDataType 枚举，排除 UNKNOWN）。 */
export type ValueDataType = 'LONG' | 'DOUBLE' | 'DECIMAL' | 'STRING' | 'BOOLEAN' | 'DATE' | 'DATETIME' | 'LIST';

/** @deprecated 使用 ValueDataType。保留兼容旧引用（RuleBodyEditor/ScriptEditor/SlotValueInput）。 */
export type DataType = ValueDataType;

/** Slot 值约束（可选）。 */
export interface SlotConstraint {
  min?: number | null;
  max?: number | null;
  enumValues?: string[] | null;
  /** METRIC_REF 专用：限制 metric 的 dataType 兼容范围。 */
  allowedDataTypes?: string[] | null;
}

/**
 * 模板 Slot 参数定义。
 * dataType 仅 kind=VALUE 时有值；REF slot 不填。
 */
export interface TemplateSlot {
  key: string;
  label: string;
  kind: SlotKind;
  dataType?: ValueDataType;
  required: boolean;
  constraint?: SlotConstraint | null;
}

/** JSON Pointer 寻址 bodySkeleton 内具体位置（RFC 6901）。 */
export interface JsonPointerTarget {
  type: 'JsonPointerTarget';
  jsonPointer: string;
}

/** slot 绑定目标——多态，按 type 判别。 */
export type SlotTarget = JsonPointerTarget;

/** slot→body 位置的显式绑定。 */
export interface SlotBinding {
  slotKey: string;
  target: SlotTarget;
}

/**
 * 模板身份层（对齐后端 RuleTemplate 实体）。
 * 列表端点返回此类型；不含 body/slots/bindings/version。
 */
export interface RuleTemplate {
  id: number;
  tenantId: number;
  code: string;
  name: string;
  description?: string;
  kind: RuleKind;
  status: TemplateStatus;
  createdBy?: string;
  createdAt?: string;
  updatedBy?: string;
  updatedAt?: string;
}

/**
 * 模板版本快照（对齐后端 RuleTemplateVersion 实体）。
 * 含 bodySkeleton/slots/bindings/version，不可变。
 */
export interface RuleTemplateVersion {
  id: number;
  templateId: number;
  version: number;
  bodySkeleton: RuleBody;
  slots: TemplateSlot[];
  bindings: SlotBinding[];
  status: TemplateStatus;
  createdBy?: string;
  createdAt?: string;
}

/**
 * 模板详情（身份 + 版本快照组合）。
 * GET /{code} 返回此类型。
 */
export interface TemplateDetail {
  template: RuleTemplate;
  version: RuleTemplateVersion;
}
