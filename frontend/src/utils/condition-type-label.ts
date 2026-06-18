import type { TFunction } from 'i18next';

/** conditionType code → i18n key 映射（key 不含 namespace 前缀，调用方 t 已限定 ns='rule'） */
const KEY_MAP: Record<string, string> = {
  EQ: 'conditionType.EQ',
  NEQ: 'conditionType.NEQ',
  GT: 'conditionType.GT',
  GTE: 'conditionType.GTE',
  LT: 'conditionType.LT',
  LTE: 'conditionType.LTE',
  IN: 'conditionType.IN',
  NOT_IN: 'conditionType.NOT_IN',
  BETWEEN: 'conditionType.BETWEEN',
  NOT_BETWEEN: 'conditionType.NOT_BETWEEN',
  CONTAINS: 'conditionType.CONTAINS',
  NOT_CONTAINS: 'conditionType.NOT_CONTAINS',
  STARTS_WITH: 'conditionType.STARTS_WITH',
  ENDS_WITH: 'conditionType.ENDS_WITH',
  MATCHES: 'conditionType.MATCHES',
  DATE_BEFORE: 'conditionType.DATE_BEFORE',
  DATE_AFTER: 'conditionType.DATE_AFTER',
  'time.window': 'conditionType.time_window',
  'time.occurred_at': 'conditionType.time_occurred_at',
};

/** 获取 conditionType 的显示名（通过 i18n），SPI 自定义算子回退到 code */
export function conditionTypeLabel(t: TFunction, code: string): string {
  const key = KEY_MAP[code];
  return key ? t(key) : code;
}
