import { Button, Select, Space } from 'antd';
import { PlusOutlined, DeleteOutlined } from '@ant-design/icons';
import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useTenantStore } from '@/store/tenantStore';
import { useLineageStore } from '@/store/lineageStore';
import { listDecisions } from '@/api/decision';
import type { DecisionBinding, DecisionItem } from '@/types';

interface Props {
  kind: string;
  value?: DecisionBinding[];
  onChange?: (bindings: DecisionBinding[]) => void;
}

export default function DecisionBindingEditor({ kind, value = [], onChange }: Props) {
  const { t } = useTranslation('rule');
  const tl = useTranslation('lineage').t;
  const { currentId } = useTenantStore();
  const { decisionUsage, requestOpen } = useLineageStore();
  const [decisions, setDecisions] = useState<DecisionItem[]>([]);

  useEffect(() => {
    if (currentId) listDecisions(currentId).then((d) => setDecisions(d ?? []));
  }, [currentId]);

  const handleAdd = () => {
    onChange?.([...value, { decisionCode: '' }]);
  };

  const handleRemove = (index: number) => {
    const updated = value.filter((_, i) => i !== index);
    onChange?.(updated);
  };

  const handleChange = (index: number, decisionCode: string) => {
    const updated = value.map((b, i) => (i === index ? { ...b, decisionCode } : b));
    onChange?.(updated);
  };

  const decisionOptions = decisions.map((d) => ({ value: d.code, label: `${d.code} (${d.name})` }));

  return (
    <div>
      {value.map((binding, i) => {
        const count = binding.decisionCode ? decisionUsage[binding.decisionCode] ?? 0 : 0;
        return (
          <Space key={i} style={{ display: 'flex', marginBottom: 8 }} align="start">
            <Select
              value={binding.decisionCode || undefined}
              onChange={(v) => handleChange(i, v)}
              options={decisionOptions}
              placeholder={t('decisionBinding.selectPlaceholder')}
              style={{ width: 160 }}
            />
            <Button icon={<DeleteOutlined />} size="small" onClick={() => handleRemove(i)} />
            {count > 0 && (
              <a
                onClick={(e) => { e.stopPropagation(); requestOpen({ code: binding.decisionCode, kind: 'decision' }); }}
                style={{ color: '#0969da', fontSize: 12, whiteSpace: 'nowrap', alignSelf: 'center' }}
              >
                {tl('editorChip', { n: count })}
              </a>
            )}
          </Space>
        );
      })}
      {(kind !== 'AST_BOOLEAN' || value.length === 0) && (
        <Button type="dashed" icon={<PlusOutlined />} onClick={handleAdd} block>
          {t('decisionBinding.addButton')}
        </Button>
      )}
      {kind === 'AST_BOOLEAN' && value.length >= 1 && (
        <div style={{ color: '#999', fontSize: 12, marginTop: 4 }}>{t('decisionBinding.singleOnlyHint')}</div>
      )}
    </div>
  );
}
