import type { CompletionContext, CompletionResult } from '@codemirror/autocomplete';
import type { MetricDescriptor } from '@/types';

/**
 * 六引擎通用的表达式补全源：命名空间 metrics. / payload. / subject. / params.（模板常量）跨引擎共享。
 * 顶层变量（now / subjectId / tenantId）不依赖 "." 触发，在任意位置都可补全。
 *
 * @param ctx CodeMirror 补全上下文
 * @param metrics 可用指标描述符，供 metrics.<code> 补全
 * @param payloadFields 事件载荷字段名，供 payload.<field> 补全
 * @param payloadTypes 载荷字段名→类型映射，用于补全项的类型提示
 * @param paramKeys 本规则冻结常量键集，供 params.<键> 补全
 * @return 补全结果；无匹配时返回 null
 */
export function expressionCompletions(
  ctx: CompletionContext,
  metrics: MetricDescriptor[],
  payloadFields: string[],
  payloadTypes: Record<string, string>,
  paramKeys: string[],
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
      { label: 'params', type: 'namespace', detail: '模板常量', info: '本规则冻结常量(params.<键>)' },
    ].filter((b) => b.label.toLowerCase().startsWith(partial));
    if (builtins.length > 0) {
      return { from: topWord.from, options: builtins };
    }
  }

  // 命名空间.字段补全
  const nsWord = ctx.matchBefore(/(?:metrics|payload|subject|params)\.(\w*)/);
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
  if (prefix === 'params') {
    return {
      from: nsWord.from + 'params.'.length,
      options: paramKeys
        .filter((k) => k.startsWith(partial))
        .map((k) => ({
          label: k,
          type: 'property',
          detail: '常量',
        })),
    };
  }

  return null;
}
