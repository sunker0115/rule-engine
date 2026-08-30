import { describe, it, expect } from 'vitest';
import type { CompletionContext } from '@codemirror/autocomplete';
import type { MetricDescriptor } from '@/types';
import { expressionCompletions } from './expressionCompletions';

/**
 * 最小 CompletionContext 桩：本函数只用到 ctx.matchBefore(regex)。
 * 复刻 CodeMirror 语义——matchBefore 把正则末尾锚定在光标处（等价于 source + '$'），
 * 命中则返回 { from, to, text }，否则 null。这里游标恒在 before 串尾。
 */
function makeCtx(before: string): CompletionContext {
  return {
    pos: before.length,
    matchBefore(re: RegExp) {
      const anchored = new RegExp(re.source + '$');
      const found = before.search(anchored);
      if (found < 0) return null;
      return { from: found, to: before.length, text: before.slice(found) };
    },
  } as unknown as CompletionContext;
}

const metrics: MetricDescriptor[] = [
  {
    metricCode: 'txn_count',
    metricVersion: 1,
    name: '交易笔数',
    sourceType: 'SQL_AGGREGATE',
    dataType: 'LONG',
    allowProvided: false,
    cacheTtlSeconds: 0,
  },
];
const payloadFields = ['amount', 'currency'];
const payloadTypes: Record<string, string> = { amount: 'DOUBLE', currency: 'STRING' };

describe('expressionCompletions params 命名空间', () => {
  it('顶层 par 前缀建议含 params 命名空间项', () => {
    const res = expressionCompletions(makeCtx('par'), metrics, payloadFields, payloadTypes, []);
    expect(res).not.toBeNull();
    const labels = res!.options.map((o) => o.label);
    expect(labels).toContain('params');
    const item = res!.options.find((o) => o.label === 'params');
    expect(item?.type).toBe('namespace');
  });

  it('params.th + paramKeys 前缀过滤出 threshold/thd', () => {
    const res = expressionCompletions(makeCtx('params.th'), metrics, payloadFields, payloadTypes, [
      'threshold',
      'thd',
      'other',
    ]);
    expect(res).not.toBeNull();
    const labels = res!.options.map((o) => o.label);
    expect(labels).toEqual(['threshold', 'thd']);
    expect(res!.options[0].type).toBe('property');
    expect(res!.options[0].detail).toBe('常量');
  });

  it('params 分支不受空 paramKeys 影响（返回空选项）', () => {
    const res = expressionCompletions(makeCtx('params.'), metrics, payloadFields, payloadTypes, []);
    expect(res).not.toBeNull();
    expect(res!.options).toEqual([]);
  });

  it('metrics. 分支回归不变', () => {
    const res = expressionCompletions(makeCtx('metrics.txn'), metrics, payloadFields, payloadTypes, []);
    expect(res).not.toBeNull();
    expect(res!.options.map((o) => o.label)).toEqual(['txn_count']);
  });

  it('payload. 分支回归不变', () => {
    const res = expressionCompletions(makeCtx('payload.am'), metrics, payloadFields, payloadTypes, []);
    expect(res).not.toBeNull();
    expect(res!.options.map((o) => o.label)).toEqual(['amount']);
  });
});
