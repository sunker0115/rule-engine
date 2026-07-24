import type { RuleBody, RuleKind } from './rule';

export type TemplateStatus = 'DRAFT' | 'PUBLISHED' | 'DISABLED';

/**
 * slot dataType 取值——对齐后端 kernel DataType 契约 tag（不含运行时哨兵 UNKNOWN）。
 * 前端无共享 DataType 类型，故就地定义此并集。
 */
export type DataType = 'LONG' | 'DOUBLE' | 'DECIMAL' | 'STRING' | 'BOOLEAN' | 'DATE' | 'DATETIME' | 'LIST';

/** Slot 值约束（可选）。 */
export interface SlotConstraint {
  min?: number | null;
  max?: number | null;
  enumValues?: string[] | null;
}

/**
 * 模板 Slot 参数定义。
 * 无 defaultValue——默认值 = bodySkeleton 在该 slot 对应 binding 位置的当前值。
 */
export interface TemplateSlot {
  key: string;
  label: string;
  dataType: DataType;
  required: boolean;
  constraint?: SlotConstraint | null;
}

/** JSON Pointer 寻址 bodySkeleton 内具体位置（RFC 6901）。 */
export interface JsonPointerTarget {
  type: 'JsonPointerTarget';
  jsonPointer: string;
}

/** slot 绑定目标——多态，按 type 判别（当前仅 JsonPointerTarget）。 */
export type SlotTarget = JsonPointerTarget;

/** slot→body 位置的显式绑定（sidecar，非 token）。 */
export interface SlotBinding {
  slotKey: string;
  target: SlotTarget;
}

export interface RuleTemplate {
  id: number;
  code: string;
  tenantId: number;
  name: string;
  description?: string;
  kind: RuleKind;
  /** body 骨架：合法 body，binding 位置填有默认值，无 token。 */
  bodySkeleton: RuleBody;
  slots: TemplateSlot[];
  bindings: SlotBinding[];
  version: number;
  status: TemplateStatus;
  createdBy?: string;
  createdAt?: string;
  updatedBy?: string;
  updatedAt?: string;
}
