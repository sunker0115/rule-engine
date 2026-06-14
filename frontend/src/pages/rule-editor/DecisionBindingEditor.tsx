import { Button, Select, Space, InputNumber } from 'antd';
import { PlusOutlined, DeleteOutlined } from '@ant-design/icons';
import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useTenantStore } from '@/store/tenantStore';
import { listDecisions } from '@/api/decision';
import type { DecisionBinding, DecisionItem } from '@/types';

interface Props {
  kind: string;
  value?: DecisionBinding[];
  onChange?: (bindings: DecisionBinding[]) => void;
}

export default function DecisionBindingEditor({ kind, value = [], onChange }: Props) {
  const { t } = useTranslation('rule');
  const tc = useTranslation('common').t;
  const { currentId } = useTenantStore();
  const [decisions, setDecisions] = useState<DecisionItem[]>([]);

  useEffect(() => {
    if (currentId) listDecisions(currentId).then((d) => setDecisions(d.data ?? []));
  }, [currentId]);

  const handleAdd = () => {
    onChange?.([...value, { decisionCode: '' }]);
  };

  const handleRemove = (index: number) => {
    const updated = value.filter((_, i) => i !== index);
    onChange?.(updated);
  };

  const handleChange = (index: number, field: keyof DecisionBinding, val: unknown) => {
    const updated = value.map((b, i) => (i === index ? { ...b, [field]: val } : b));
    onChange?.(updated);
  };

  const decisionOptions = decisions.map((d) => ({ value: d.code, label: `${d.code} (${d.name})` }));

  return (
    <div>
      {value.map((binding, i) => (
        <Space key={i} style={{ display: 'flex', marginBottom: 8 }} align="start">
          <Select
            value={binding.decisionCode || undefined}
            onChange={(v) => handleChange(i, 'decisionCode', v)}
            options={decisionOptions}
            placeholder={t('decisionBinding.selectPlaceholder')}
            style={{ width: 160 }}
          />
          {kind === 'SCORECARD' && (
            <>
              <InputNumber
                placeholder={t('decisionBinding.scoreRangeMin')}
                value={binding.scoreRangeMin}
                onChange={(v) => handleChange(i, 'scoreRangeMin', v)}
                style={{ width: 80 }}
              />
              <InputNumber
                placeholder={t('decisionBinding.scoreRangeMax')}
                value={binding.scoreRangeMax}
                onChange={(v) => handleChange(i, 'scoreRangeMax', v)}
                style={{ width: 80 }}
              />
            </>
          )}
          <Button icon={<DeleteOutlined />} size="small" onClick={() => handleRemove(i)} />
        </Space>
      ))}
      <Button type="dashed" icon={<PlusOutlined />} onClick={handleAdd} block>
        {tc('button.confirm')}
      </Button>
    </div>
  );
}
