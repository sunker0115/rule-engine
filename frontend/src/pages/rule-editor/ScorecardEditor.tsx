import { Button, InputNumber, Typography } from 'antd';
import { PlusOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import type { ScorecardRootNode, ConditionNode, ConditionTypeMeta, MetricDescriptor } from '@/types';
import ConditionCard from './ConditionCard';

interface Props {
  node: ScorecardRootNode;
  conditionTypes: ConditionTypeMeta[];
  availableMetrics: MetricDescriptor[];
  payloadFieldNames: string[];
  onChange: (node: ScorecardRootNode) => void;
}

function emptyCondition(): ConditionNode {
  return { type: 'ConditionNode', conditionType: '', params: {}, weight: 0 };
}

export default function ScorecardEditor({ node, conditionTypes, availableMetrics, payloadFieldNames, onChange }: Props) {
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
    </div>
  );
}
