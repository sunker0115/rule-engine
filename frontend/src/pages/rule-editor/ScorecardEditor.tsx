import { Button, Input, InputNumber, Select, Space, Typography } from 'antd';
import { PlusOutlined, DeleteOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import type { ScorecardRootNode, ScoreBand, ConditionNode, ConditionTypeMeta, MetricDescriptor } from '@/types';
import ConditionCard from './ConditionCard';

interface Props {
  node: ScorecardRootNode;
  conditionTypes: ConditionTypeMeta[];
  availableMetrics: MetricDescriptor[];
  payloadFieldNames: string[];
  decisions: { code: string; name: string }[];
  onChange: (node: ScorecardRootNode) => void;
}

function emptyCondition(): ConditionNode {
  return { type: 'ConditionNode', conditionType: '', params: {}, weight: 0 };
}

export default function ScorecardEditor({ node, conditionTypes, availableMetrics, payloadFieldNames, decisions, onChange }: Props) {
  const { t } = useTranslation('rule');
  const updateCondition = (index: number, c: ConditionNode) => {
    const conditions = [...node.conditions];
    conditions[index] = c;
    onChange({ ...node, conditions });
  };

  const removeCondition = (index: number) => {
    const conditions = node.conditions.filter((_, i) => i !== index);
    onChange({ ...node, conditions });
  };

  const addCondition = () => {
    onChange({ ...node, conditions: [...node.conditions, emptyCondition()] });
  };

  const bands = node.bands ?? [];

  const updateBand = (i: number, patch: Partial<ScoreBand>) =>
    onChange({ ...node, bands: bands.map((b, idx) => (idx === i ? { ...b, ...patch } : b)) });
  const addBand = () =>
    onChange({ ...node, bands: [...bands, { minScore: 0, maxScore: 0, decisionCode: '', category: null }] });
  const removeBand = (i: number) =>
    onChange({ ...node, bands: bands.filter((_, idx) => idx !== i) });

  const decisionOptions = decisions.map((d) => ({ value: d.code, label: `${d.code} (${d.name})` }));

  // 前端轻校验（即时提示，非阻断）：min<max、相邻段重叠
  const bandError = (i: number): string | null => {
    const b = bands[i];
    if (b.minScore >= b.maxScore) return t('editor.scorecard.bandOverlap');
    const overlap = bands.some((o, idx) =>
      idx !== i && b.minScore < o.maxScore && o.minScore < b.maxScore);
    return overlap ? t('editor.scorecard.bandOverlap') : null;
  };

  return (
    <div>
      {/* 阈值 */}
      <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: 16 }}>
        <Typography.Text strong>{t('editor.scorecard.threshold')}</Typography.Text>
        <InputNumber
          min={0}
          value={node.threshold}
          onChange={(v) => onChange({ ...node, threshold: v ?? 0 })}
          style={{ width: 100 }}
        />
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          {t('editor.scorecard.thresholdHint')}
        </Typography.Text>
        <div style={{ flex: 1 }} />
        <Button type="primary" icon={<PlusOutlined />} onClick={addCondition}>{t('editor.scorecard.addItem')}</Button>
      </div>

      {/* 评分项列表 */}
      {node.conditions.length === 0 && (
        <div style={{ padding: 40, textAlign: 'center', color: '#ccc' }}>
          {t('editor.scorecard.emptyHint')}
        </div>
      )}
      {node.conditions.map((c, index) => (
        <div key={index} style={{ display: 'flex', alignItems: 'flex-start', gap: 8, marginBottom: 8 }}>
          {/* 权重 */}
          <div style={{ paddingTop: 10 }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 6, background: '#fafafa', borderRadius: 4, padding: '4px 8px', border: '1px solid #d9d9d9' }}>
              <span style={{ fontSize: 12, color: '#999' }}>{t('editor.scorecard.weight')}</span>
              <InputNumber
                size="small"
                min={0}
                style={{ width: 70 }}
                value={c.weight ?? 0}
                onChange={(v) => updateCondition(index, { ...c, weight: v ?? 0 })}
              />
            </div>
          </div>
          <div style={{ flex: 1 }}>
            <ConditionCard
              node={c}
              conditionTypes={conditionTypes}
              availableMetrics={availableMetrics}
              payloadFieldNames={payloadFieldNames}
              onChange={(n) => updateCondition(index, n)}
              onDelete={() => removeCondition(index)}
            />
          </div>
        </div>
      ))}

      {/* 分段决策 */}
      <div style={{ marginTop: 24, paddingTop: 16, borderTop: '1px solid #f0f0f0' }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: 12 }}>
          <Typography.Text strong>{t('editor.scorecard.bandsTitle')}</Typography.Text>
          <div style={{ flex: 1 }} />
          <Button icon={<PlusOutlined />} onClick={addBand}>{t('editor.scorecard.addBand')}</Button>
        </div>
        {bands.length === 0 && (
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            {t('editor.scorecard.bandsEmptyHint')}
          </Typography.Text>
        )}
        {bands.map((b, i) => {
          const err = bandError(i);
          return (
            <div key={i} style={{ marginBottom: 8 }}>
              <Space align="start">
                <InputNumber
                  value={b.minScore}
                  onChange={(v) => updateBand(i, { minScore: v ?? 0 })}
                  placeholder={t('editor.scorecard.bandMin')}
                  status={err ? 'error' : undefined}
                  style={{ width: 110 }}
                />
                <InputNumber
                  value={b.maxScore}
                  onChange={(v) => updateBand(i, { maxScore: v ?? 0 })}
                  placeholder={t('editor.scorecard.bandMax')}
                  status={err ? 'error' : undefined}
                  style={{ width: 110 }}
                />
                <Select
                  value={b.decisionCode || undefined}
                  onChange={(v) => updateBand(i, { decisionCode: v })}
                  options={decisionOptions}
                  placeholder={t('editor.scorecard.bandDecision')}
                  style={{ width: 180 }}
                />
                <Input
                  value={b.category ?? ''}
                  onChange={(e) => updateBand(i, { category: e.target.value || null })}
                  placeholder={t('editor.scorecard.bandCategory')}
                  style={{ width: 130 }}
                />
                <Button icon={<DeleteOutlined />} onClick={() => removeBand(i)} />
              </Space>
              {err && (
                <Typography.Text type="danger" style={{ fontSize: 12, marginLeft: 8 }}>
                  {err}
                </Typography.Text>
              )}
            </div>
          );
        })}
      </div>
    </div>
  );
}
