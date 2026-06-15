import dayjs from 'dayjs';

/**
 * 格式化日期时间字符串。
 * @param v   ISO 时间字符串（可为 null/undefined）
 * @param fmt dayjs 格式串，默认 'YYYY-MM-DD HH:mm:ss'
 * @returns   格式化后的时间，null/undefined 返回 '-'
 */
export function formatDateTime(v: string | null | undefined, fmt: string = 'YYYY-MM-DD HH:mm:ss'): string {
  if (!v) return '-';
  const d = dayjs(v);
  return d.isValid() ? d.format(fmt) : '-';
}
