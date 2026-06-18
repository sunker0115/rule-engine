import { useState, useCallback } from 'react';
import type { LineageRuleRef } from '@/types';

/** fetcher 形如 getDecisionSources / 单 metric 血缘——返回 { sources } 即可，组件不绑定具体来源。 */
export type LineageFetcher = (tenantId: number, code: string) => Promise<{ sources: LineageRuleRef[] }>;

/**
 * 血缘数据 hook：用传入的 fetcher 拉取「产出 / 引用某资源的规则」列表。
 * 暴露 loading、rows，及 load(tenantId,code) / reset()——load 内 try/finally 控 loading。
 */
export function useLineage(fetcher: LineageFetcher) {
  const [loading, setLoading] = useState(false);
  const [rows, setRows] = useState<LineageRuleRef[]>([]);

  const load = useCallback(
    async (tenantId: number, code: string) => {
      setLoading(true);
      try {
        const res = await fetcher(tenantId, code);
        setRows(res.sources ?? []);
      } finally {
        setLoading(false);
      }
    },
    [fetcher],
  );

  const reset = useCallback(() => setRows([]), []);

  return { loading, rows, load, reset };
}
