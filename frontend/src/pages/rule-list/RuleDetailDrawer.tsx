import { useEffect, useState, useMemo } from 'react';
import { Drawer, Descriptions, Tag, Spin, Timeline, Tabs, Empty } from 'antd';
import { useTranslation } from 'react-i18next';
import { useTenantStore } from '@/store/tenantStore';
import { getRule } from '@/api/rule';
import { colorOf, getRuleStatusOptions, getVersionStatusOptions } from '@/constants/enums';
import { formatDateTime } from '@/utils/format';
import type { RuleDetail as RuleDetailType, IfNode, RolloutParams } from '@/types';

/** 从 AST 递归提取所有 DecisionLeafNode 的 decisionCode */
function extractDecisionCodes(node: unknown): string[] {
  if (!node || typeof node !== 'object') return [];
  const n = node as Record<string, unknown>;
  if (n.type === 'DecisionLeafNode') return [n.decisionCode as string].filter(Boolean);
  if (n.type === 'IfNode') {
    const ifNode = node as IfNode;
    return [
      ...extractDecisionCodes(ifNode.condition),
      ...extractDecisionCodes(ifNode.thenBranch),
      ...extractDecisionCodes(ifNode.elseBranch),
    ];
  }
  // 容器节点：AndNode / OrNode / NotNode / XorNode
  if (Array.isArray(n.children)) {
    return (n.children as unknown[]).flatMap(extractDecisionCodes);
  }
  if (n.child) return extractDecisionCodes(n.child);
  if (Array.isArray(n.conditions)) return (n.conditions as unknown[]).flatMap(extractDecisionCodes);
  if (Array.isArray(n.rows)) {
    return (n.rows as Array<{ decisionCode?: string }>).map(r => r.decisionCode).filter(Boolean) as string[];
  }
  return [];
}

interface Props {
  open: boolean;
  ruleDefinitionId: number | null;
  onClose: () => void;
}

export default function RuleDetailDrawer({ open, ruleDefinitionId, onClose }: Props) {
  const { t } = useTranslation('rule');
  const tc = useTranslation('common').t;
  const ruleStatusOpts = useMemo(() => getRuleStatusOptions(t), [t]);
  const versionStatusOpts = useMemo(() => getVersionStatusOptions(t), [t]);
  const { currentId } = useTenantStore();
  const [detail, setDetail] = useState<RuleDetailType | null>(null);
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    if (open && ruleDefinitionId && currentId) {
      setLoading(true);
      getRule(currentId, ruleDefinitionId)
        .then((res) => setDetail(res.data ?? null))
        .finally(() => setLoading(false));
    }
    return () => setDetail(null);
  }, [open, ruleDefinitionId, currentId]);

  if (loading) return <Drawer title={t('detail.title')} open={open} onClose={onClose} width={520}><Spin /></Drawer>;
  if (!detail) return <Drawer title={t('detail.title')} open={open} onClose={onClose} width={520}><Empty /></Drawer>;

  // 决策码：优先 decisionBindings，AST_BOOLEAN/SCORECARD 绑在右面板；DECISION_TREE/TABLE 从 AST 提取
  const boundCodes = (detail.decisionBindings ?? []).map((b) => b.decisionCode);
  const astCodes = extractDecisionCodes(detail.conditionAst);
  const decisionCodes = boundCodes.length > 0 ? boundCodes : [...new Set(astCodes)];

  // 找当前生效版本
  const activeVersion = detail.versions?.find((v) => v.status === 'ACTIVE');
  const draftVersion = detail.versions?.find((v) => v.status === 'DRAFT');

  return (
    <Drawer title={`${detail.code}`} open={open} onClose={onClose} width={520}>
      <Tabs
        items={[
          {
            key: 'info',
            label: t('detail.basicInfo'),
            children: (
              <Descriptions column={1} size="small" bordered>
                <Descriptions.Item label={t('column.code')}>{detail.code}</Descriptions.Item>
                <Descriptions.Item label={tc('label.name')}>{detail.name}</Descriptions.Item>
                <Descriptions.Item label={t('column.kind')}><Tag>{detail.kind}</Tag></Descriptions.Item>
                <Descriptions.Item label={t('detail.label.scene')}>{detail.sceneCode}</Descriptions.Item>
                <Descriptions.Item label={tc('label.status')}>
                  <Tag color={colorOf(ruleStatusOpts, detail.status)}>{detail.status}</Tag>
                </Descriptions.Item>
                <Descriptions.Item label={t('detail.label.triggerEvents')}>
                  {(detail.triggerEventTypes ?? []).join(', ') || '-'}
                </Descriptions.Item>
                <Descriptions.Item label={t('detail.label.decision')}>
                  {decisionCodes.length > 0 ? decisionCodes.join(', ') : '-'}
                </Descriptions.Item>
                <Descriptions.Item label={t('detail.label.preGate')}>
                  {(detail.preGates ?? []).length > 0
                    ? (detail.preGates ?? [])
                        .map((g) =>
                          g.gateType === 'ROLLOUT'
                            ? `${t('preGate.labelRollout')} ${(g.params as RolloutParams)?.percentage ?? '?'}%`
                            : t('preGate.timeWindowTitle'),
                        )
                        .join('; ')
                    : '-'}
                </Descriptions.Item>
                {activeVersion && (
                  <Descriptions.Item label={t('detail.label.activeVersion')}>v{activeVersion.version}</Descriptions.Item>
                )}
                {draftVersion && (
                  <Descriptions.Item label={t('detail.label.draftVersion')}>
                    <Tag color="blue">v{draftVersion.version}</Tag>
                  </Descriptions.Item>
                )}
              </Descriptions>
            ),
          },
          {
            key: 'versions',
            label: `${t('detail.versionHistory')} (${detail.versions?.length ?? 0})`,
            children: detail.versions && detail.versions.length > 0 ? (
              <Timeline
                items={detail.versions.map((v) => ({
                  color: v.status === 'ACTIVE' ? 'green' : v.status === 'DRAFT' ? 'blue' : 'gray',
                  children: (
                    <div>
                      <Tag color={colorOf(versionStatusOpts, v.status)}>v{v.version}</Tag>
                      <div style={{ fontSize: 12, color: '#999', marginTop: 4 }}>
                                                {formatDateTime(v.createdAt, 'YYYY-MM-DD HH:mm')}
                        {v.publishedBy && <span> · {v.publishedBy}</span>}
                      </div>
                    </div>
                  ),
                }))}
              />
            ) : <Empty description={t('detail.label.noVersion')} />,
          },
        ]}
      />
    </Drawer>
  );
}
