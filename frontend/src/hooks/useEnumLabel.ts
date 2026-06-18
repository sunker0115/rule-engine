import { useTranslation } from 'react-i18next';

/**
 * 枚举标签 hook —— 桥接 constants/enums.ts 与 i18n 翻译文件。
 *
 * 用法：
 *   const et = useEnumLabel('scene', 'enum.dominantMode');
 *   <Tag>{et('PUSH')}</Tag>  // → "PUSH (异步评估)" [zh-CN]
 *
 * 新增枚举值时：
 *   1. constants/enums.ts 加 value
 *   2. i18n/types.ts 对应接口加 key
 *   3. i18n/locales/zh-CN/ 对应文件加 label
 *   组件无需改动。
 */
export function useEnumLabel(
  ns: string,
  prefix: string,
): (value: string) => string {
  const { t } = useTranslation(ns);
  return (value: string) => t(`${prefix}.${value}`);
}
