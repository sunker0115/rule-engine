/** 参数控件类型 */
export type ParamWidget = 'text' | 'number' | 'array' | 'time-range' | 'operator-select';

/** 已知参数键 → 显示名 + 控件类型 */
const PARAM_REGISTRY: Record<string, { label: string; widget: ParamWidget }> = {
  threshold:    { label: '阈值', widget: 'number' },
  min:          { label: '下限', widget: 'number' },
  max:          { label: '上限', widget: 'number' },
  values:       { label: '候选值', widget: 'array' },
  element:      { label: '元素', widget: 'text' },
  prefix:       { label: '前缀', widget: 'text' },
  suffix:       { label: '后缀', widget: 'text' },
  regex:        { label: '正则', widget: 'text' },
  operator:     { label: '运算符', widget: 'operator-select' },
  start:        { label: '开始时间', widget: 'text' },
  end:          { label: '结束时间', widget: 'text' },
  value:        { label: '比较值', widget: 'text' },
  timezone:     { label: '时区', widget: 'text' },
  datesExclude: { label: '排除日期', widget: 'array' },
  daysOfWeek:   { label: '生效星期', widget: 'array' },
};

/** 获取参数显示名 */
export function paramLabel(key: string): string {
  return PARAM_REGISTRY[key]?.label ?? key;
}

/** 获取参数控件类型；未知键默认 text */
export function paramWidget(key: string): ParamWidget {
  return PARAM_REGISTRY[key]?.widget ?? 'text';
}
