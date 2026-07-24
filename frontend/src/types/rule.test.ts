import { describe, it, expect } from 'vitest';
import { bodyToCarriers, carriersToBody } from './rule';

describe('脚本载体 params round-trip', () => {
  it('bodyToCarriers 保留 script.params', () => {
    const carriers = bodyToCarriers({
      type: 'ScriptBody',
      script: { source: 'x', lang: 'CEL', params: { a: 1 } },
    });
    expect(carriers.script?.params).toEqual({ a: 1 });
  });

  it('carriersToBody 保留 script.params', () => {
    const body = carriersToBody('EXPRESSION_SCRIPT', {
      script: { source: 'x', lang: 'CEL', params: { a: 1 } },
    });
    expect(body.type).toBe('ScriptBody');
    if (body.type === 'ScriptBody') {
      expect(body.script.params).toEqual({ a: 1 });
    }
  });

  it('body → carriers → body 往返不丢 params', () => {
    const original = {
      type: 'ScriptBody' as const,
      script: { source: 's', lang: 'JSONLOGIC', params: { threshold: 10, tag: 'vip' } },
    };
    const carriers = bodyToCarriers(original);
    const back = carriersToBody('EXPRESSION_SCRIPT', { script: carriers.script });
    expect(back).toEqual(original);
  });
});
