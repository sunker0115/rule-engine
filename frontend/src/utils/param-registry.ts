import i18n from '@/i18n';

/** 参数控件类型 */
export type ParamWidget = 'text' | 'number' | 'array' | 'time-range' | 'operator-select';

/** 已知参数键 → 显示名 + 控件类型 */
const PARAM_REGISTRY: Record<string, { label: string; widget: ParamWidget }> = {
  threshold:    { label: i18n.t('rule.param.widget.threshold'), widget: 'number' },
  min:          { label: i18n.t('rule.param.widget.min'),       widget: 'number' },
  max:          { label: i18n.t('rule.param.widget.max'),       widget: 'number' },
  values:       { label: i18n.t('rule.param.widget.values'),    widget: 'array' },
  element:      { label: i18n.t('rule.param.widget.element'),   widget: 'text' },
  prefix:       { label: i18n.t('rule.param.widget.prefix'),    widget: 'text' },
  suffix:       { label: i18n.t('rule.param.widget.suffix'),    widget: 'text' },
  regex:        { label: i18n.t('rule.param.widget.regex'),     widget: 'text' },
  operator:     { label: i18n.t('rule.param.widget.operator'),  widget: 'operator-select' },
  start:        { label: i18n.t('rule.param.widget.start'),     widget: 'text' },
  end:          { label: i18n.t('rule.param.widget.end'),       widget: 'text' },
  value:        { label: i18n.t('rule.param.widget.value'),     widget: 'text' },
  timezone:     { label: i18n.t('rule.param.widget.timezone'),  widget: 'text' },
  datesExclude: { label: i18n.t('rule.param.widget.datesExclude'), widget: 'array' },
  daysOfWeek:   { label: i18n.t('rule.param.widget.daysOfWeek'),   widget: 'array' },
};

/** 获取参数显示名 */
export function paramLabel(key: string): string {
  return PARAM_REGISTRY[key]?.label ?? key;
}

/** 获取参数控件类型；未知键默认 text */
export function paramWidget(key: string): ParamWidget {
  return PARAM_REGISTRY[key]?.widget ?? 'text';
}
