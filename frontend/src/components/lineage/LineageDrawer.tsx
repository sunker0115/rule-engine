import { useEffect, useMemo } from 'react';
import { Drawer, Tag, Empty, Skeleton, Typography } from 'antd';
import { useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { ROUTES, route } from '@/constants/routes';
import { colorOf, getRuleStatusOptions } from '@/constants/enums';
import type { LineageRuleRef } from '@/types';
import { useLineage, type LineageFetcher } from './useLineage';

interface Props {
  open: boolean;
  /** 资源码（decisionCode / metricCode）；open && code 变化时拉取。 */
  code: string;
  /** 抽屉标题（调用方给，区分 decision「产出 X」/ metric「引用 X」）。 */
  title: string;
  tenantId: number;
  /** 数据来源 fetcher（getDecisionSources 等）。 */
  fetcher: LineageFetcher;
  onClose: () => void;
}

const MONO = 'ui-monospace, SFMono-Regular, Menlo, monospace';

/**
 * 列表 / 编辑器徽标用的卡片抽屉：镜像 RuleAnalysisDrawer 视觉。
 * 内部用 useLineage 拉数，按 sceneCode 分组，每条是带左色条的小卡，点击下钻到规则编辑器并关闭。
 */
export default function LineageDrawer({ open, code, title, tenantId, fetcher, onClose }: Props) {
  const { t } = useTranslation('lineage');
  const tr = useTranslation('rule').t;
  const navigate = useNavigate();
  const { loading, rows, load } = useLineage(fetcher);
  const ruleStatusOpts = getRuleStatusOptions(tr as never);

  useEffect(() => {
    if (open && code) load(tenantId, code);
  }, [open, code, tenantId, load]);

  /** 按 sceneCode 分组（保持出现顺序）。 */
  const groups = useMemo(() => {
    const map = new Map<string, LineageRuleRef[]>();
    for (const r of rows) {
      const arr = map.get(r.sceneCode);
      if (arr) arr.push(r);
      else map.set(r.sceneCode, [r]);
    }
    return [...map.entries()];
  }, [rows]);

  const goEditor = (ref: LineageRuleRef) => {
    navigate(route(ROUTES.RULE_EDITOR, { ruleId: ref.ruleDefinitionId }));
    onClose();
  };

  return (
    <Drawer
      title={
        <div>
          <div>{title}</div>
          <Typography.Text type="secondary" style={{ fontSize: 12, fontWeight: 400 }}>
            {t('count', { n: rows.length })}
          </Typography.Text>
        </div>
      }
      open={open}
      onClose={onClose}
      width={460}
    >
      {loading ? (
        <Skeleton active paragraph={{ rows: 6 }} />
      ) : rows.length === 0 ? (
        <Empty description={t('empty')} />
      ) : (
        groups.map(([sceneCode, refs]) => (
          <div key={sceneCode} style={{ marginBottom: 16 }}>
            <div style={{ display: 'flex', alignItems: 'center', marginBottom: 6 }}>
              <span style={{ fontWeight: 600 }}>{sceneCode}</span>
              <Typography.Text type="secondary" style={{ marginLeft: 6, fontWeight: 400 }}>
                ({refs.length})
              </Typography.Text>
            </div>
            {refs.map((ref) => (
              <div
                key={ref.ruleDefinitionId}
                onClick={() => goEditor(ref)}
                style={{
                  border: '1px solid #eaecef',
                  borderLeft: '3px solid #0969da',
                  borderRadius: 6,
                  padding: '8px 10px',
                  margin: '6px 0',
                  cursor: 'pointer',
                  fontSize: 12,
                }}
              >
                <div style={{ display: 'flex', alignItems: 'center', marginBottom: 3 }}>
                  <span style={{ fontWeight: 600, fontFamily: MONO }}>{ref.ruleCode}</span>
                  <Tag color={colorOf(ruleStatusOpts, ref.status as never)} style={{ marginLeft: 8 }}>
                    {ref.status}
                  </Tag>
                  <Typography.Text type="secondary" style={{ marginLeft: 'auto', fontSize: 11 }}>
                    {t('toEditor')}
                  </Typography.Text>
                </div>
                <div style={{ color: '#57606a', lineHeight: 1.5 }}>{ref.ruleName}</div>
              </div>
            ))}
          </div>
        ))
      )}
    </Drawer>
  );
}
