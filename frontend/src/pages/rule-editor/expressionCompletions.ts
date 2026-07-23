import type { CompletionContext, CompletionResult } from '@codemirror/autocomplete';
import type { MetricDescriptor } from '@/types';

/**
 * 六引擎通用的表达式补全源：命名空间 metrics. / payload. / subject. 跨引擎共享。
 * 顶层变量（now / subjectId / tenantId）不依赖 "." 触发，在任意位置都可补全。
 */
export function expressionCompletions(
  ctx: CompletionContext,
  metrics: MetricDescriptor[],
  payloadFields: string[],
  payloadTypes: Record<string, string>,
): CompletionResult | null {
  // 顶层变量：now、subjectId、tenantId（不以 . 结尾触发，前缀匹配即出）
  const topWord = ctx.matchBefore(/\b([a-zA-Z_]\w*)/);
  if (topWord && !topWord.text.includes('.')) {
    const partial = topWord.text.toLowerCase();
    const builtins = [
      { label: 'now', type: 'keyword', detail: 'TIMESTAMP', info: '评估时钟（Instant）' },
      { label: 'subjectId', type: 'keyword', detail: 'STRING', info: '当前主体 ID' },
      { label: 'tenantId', type: 'keyword', detail: 'STRING', info: '当前租户 ID' },
      { label: 'metrics', type: 'namespace', detail: '命名空间', info: '指标取值' },
      { label: 'payload', type: 'namespace', detail: '命名空间', info: '事件载荷字段' },
      { label: 'subject', type: 'namespace', detail: '命名空间', info: '主体属性' },
    ].filter((b) => b.label.toLowerCase().startsWith(partial));
    if (builtins.length > 0) {
      return { from: topWord.from, options: builtins };
    }
  }

  // 命名空间.字段补全
  const nsWord = ctx.matchBefore(/(?:metrics|payload|subject)\.(\w*)/);
  if (!nsWord) return null;

  const prefix = nsWord.text.split('.')[0];
  const partial = nsWord.text.split('.')[1] ?? '';

  if (prefix === 'metrics') {
    return {
      from: nsWord.from + 'metrics.'.length,
      options: metrics
        .filter((m) => m.metricCode.startsWith(partial))
        .map((m) => ({
          label: m.metricCode,
          type: 'property',
          detail: m.dataType,
          info: m.name || undefined,
        })),
    };
  }
  if (prefix === 'payload') {
    return {
      from: nsWord.from + 'payload.'.length,
      options: payloadFields
        .filter((f) => f.startsWith(partial))
        .map((f) => ({
          label: f,
          type: 'property',
          detail: payloadTypes[f] || undefined,
        })),
    };
  }
  if (prefix === 'subject') {
    // subject 字段来自 payloadSchema（如有 subject.* 声明）+ 内置字段
    const builtins = ['id', 'type'];
    const declared = payloadFields
      .filter((f) => f.startsWith('subject_')) // payload 里 subject_* 前缀字段
      .map((f) => f.replace('subject_', ''));
    const all = [...new Set([...builtins, ...declared])]
      .filter((s) => s.startsWith(partial))
      .map((s) => ({
        label: s,
        type: 'property',
        detail: s === 'id' || s === 'type' ? 'STRING' : undefined,
      }));
    return { from: nsWord.from + 'subject.'.length, options: all };
  }

  return null;
}
