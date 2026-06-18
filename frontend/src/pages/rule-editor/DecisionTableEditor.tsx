import { useEffect, useState } from 'react';
import { Button, Select, Input, InputNumber, Table, Popconfirm } from 'antd';
import { PlusOutlined, DeleteOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { useTenantStore } from '@/store/tenantStore';
import { listDecisions } from '@/api/decision';
import type { DecisionTableNode, DecisionTableColumn, DecisionTableRow, ConditionTypeMeta, MetricDescriptor } from '@/types';
import { conditionTypeLabel } from '@/utils/condition-type-label';

interface Props {
  node: DecisionTableNode;
  conditionTypes: ConditionTypeMeta[];
  availableMetrics: MetricDescriptor[];
  payloadFieldNames: string[];
  onChange: (node: DecisionTableNode) => void;
}

export default function DecisionTableEditor({
  node, conditionTypes, availableMetrics, payloadFieldNames, onChange,
}: Props) {
  const { t } = useTranslation('rule');
  const { currentId } = useTenantStore();
  const [decisions, setDecisions] = useState<{ value: string; label: string }[]>([]);

  useEffect(() => {
    if (currentId) listDecisions(currentId).then((d) => {
      setDecisions((d ?? []).map((item) => ({ value: item.code, label: `${item.code} (${item.name})` })));
    });
  }, [currentId]);

  const cols: DecisionTableColumn[] = node.columns ?? [];
  const rows: DecisionTableRow[] = node.rows ?? [];

  useEffect(() => {
    const first = availableMetrics[0]?.metricCode || payloadFieldNames[0] || '';
    if (!first) return;
    if (cols.length === 0) {
      onChange({
        ...node,
        columns: [{ metricCode: first, operator: 'EQ', dataType: null }],
        rows: [{ conditions: [null], decisionCode: '' }],
      });
      return;
    }
    const fixed = cols.map((c) => (c.metricCode ? c : { ...c, metricCode: first }));
    if (JSON.stringify(fixed) !== JSON.stringify(cols)) onChange({ ...node, columns: fixed });
  }, []);

  const TABLE_OPERATORS = ['EQ', 'NEQ', 'GT', 'GTE', 'LT', 'LTE', 'IN', 'NOT_IN', 'BETWEEN', 'NOT_BETWEEN'];
  const opOptions = conditionTypes
    .filter((ct) => TABLE_OPERATORS.includes(ct.code))
    .map((ct) => ({ value: ct.code, label: conditionTypeLabel(t, ct.code) }));

  const addColumn = () => {
    // 默认取首个可用 metric/payload，避免新增列 metricCode='' 持久化为无效条件列（与初始列一致）
    const first = availableMetrics[0]?.metricCode || payloadFieldNames[0] || '';
    const newCols = [...cols, { metricCode: first, operator: 'EQ', dataType: null }];
    const newRows = rows.map((r) => ({ ...r, conditions: [...r.conditions, null] }));
    onChange({ ...node, columns: newCols, rows: newRows });
  };

  const updateColumn = (ci: number, field: keyof DecisionTableColumn, value: unknown) => {
    const newCols = cols.map((c, i) => (i === ci ? { ...c, [field]: value } : c));
    onChange({ ...node, columns: newCols });
  };

  const removeColumn = (ci: number) => {
    if (cols.length <= 1) return;
    const newCols = cols.filter((_, i) => i !== ci);
    const newRows = rows.map((r) => ({ ...r, conditions: r.conditions.filter((_, i) => i !== ci) }));
    onChange({ ...node, columns: newCols, rows: newRows });
  };

  const addRow = () => {
    onChange({ ...node, rows: [...rows, { conditions: cols.map(() => null), decisionCode: '' }] });
  };

  const updateRow = (ri: number, field: 'decisionCode' | 'condition', ci: number, value: unknown) => {
    const newRows = rows.map((r, i) => {
      if (i !== ri) return r;
      if (field === 'decisionCode') return { ...r, decisionCode: value as string };
      const newConds = [...r.conditions];
      newConds[ci] = value;
      return { ...r, conditions: newConds };
    });
    onChange({ ...node, rows: newRows });
  };

  const removeRow = (ri: number) => onChange({ ...node, rows: rows.filter((_, i) => i !== ri) });

  return (
    <div style={{ padding: 8 }}>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'flex-end', gap: 8, marginBottom: 12 }}>
        <Button size="small" icon={<PlusOutlined />} onClick={addColumn}>{t('editor.decisionTable.addColumn')}</Button>
        <Button size="small" icon={<PlusOutlined />} onClick={addRow}>{t('editor.decisionTable.addRow')}</Button>
      </div>

      <Table
        dataSource={rows.map((r, ri) => {
          const base: Record<string, unknown> = { _key: ri, _decisionCode: r.decisionCode };
          cols.forEach((_, ci) => { base[`_c${ci}`] = r.conditions[ci]; });
          return base;
        })}
        rowKey="_key"
        size="small"
        pagination={false}
        scroll={{ x: cols.length > 2 ? 'max-content' : undefined }}
        locale={{ emptyText: t('editor.decisionTable.emptyRowHint') }}
        footer={() => (
          <Button type="dashed" size="small" icon={<PlusOutlined />} onClick={addRow} block>{t('editor.decisionTable.addRow')}</Button>
        )}
      >
        {cols.map((col, ci) => {
          const isPayload = col.valueRef === 'PAYLOAD';
          const fieldOptions = isPayload
            ? payloadFieldNames.map((f) => ({ value: f, label: f }))
            : availableMetrics.map((m) => ({ value: m.metricCode, label: m.metricCode }));
          // 区间/集合算子的格子要塞两个输入或标签，列加宽
          const isRange = col.operator === 'BETWEEN' || col.operator === 'NOT_BETWEEN';
          const isIn = col.operator === 'IN' || col.operator === 'NOT_IN';
          const cellWidth = isRange ? 150 : isIn ? 140 : 85;
          return (
            <Table.Column
              key={ci}
              width={cellWidth}
              dataIndex={`_c${ci}`}
              title={(
                <div style={{
                  border: '1px solid #d9d9d9', borderRadius: 6,
                  borderLeft: `3px solid ${isPayload ? '#fa8c16' : '#1890ff'}`,
                }}>
                  <div style={{
                    fontSize: 11, padding: '2px 4px',
                    borderBottom: '1px solid #f0f0f0', background: '#fafafa',
                    borderRadius: '0 6px 0 0', display: 'flex', alignItems: 'center', justifyContent: 'space-between',
                  }}>
                    <span style={{ color: '#999' }}>C{ci + 1}</span>
                    <Button type="text" size="small" danger icon={<DeleteOutlined />}
                      onClick={() => removeColumn(ci)} style={{ fontSize: 10, padding: 0, height: 16 }} />
                  </div>
                  <div style={{ display: 'flex', flexDirection: 'column', gap: 3, padding: '4px 6px 6px' }}>
                    <Select
                      size="small"
                      style={{ width: '100%' }}
                      value={col.valueRef ?? 'METRIC'}
                      onChange={(ref) => updateColumn(ci, 'valueRef', ref)}
                      options={[
                        { value: 'METRIC', label: t('editor.conditionCard.valueRefOptions.metric') },
                        { value: 'PAYLOAD', label: t('editor.conditionCard.valueRefOptions.payload') },
                      ]}
                    />
                    <Select
                      size="small"
                      showSearch
                      style={{ width: '100%' }}
                      value={col.metricCode || undefined}
                      onChange={(v) => updateColumn(ci, 'metricCode', v)}
                      placeholder={t('editor.decisionTable.metric')}
                      popupMatchSelectWidth={false}
                      options={fieldOptions}
                    />
                    <Select
                      size="small"
                      style={{ width: '100%' }}
                      value={col.operator || undefined}
                      onChange={(v) => updateColumn(ci, 'operator', v)}
                      options={opOptions}
                    />
                  </div>
                </div>
              )}
              render={(val: unknown, _: unknown, ri: number) => {
                // 列条件值的形状随算子而定（与 kernel ConditionEvaluator 约定对齐）：
                // IN/NOT_IN 收数组（"values"）、BETWEEN/NOT_BETWEEN 收二元 [lo,hi]（"min"/"max"）、其余收单值；
                // 空值统一回落 null = 该列通配
                if (isIn) {
                  return (
                    <Select
                      mode="tags"
                      size="small"
                      style={{ width: '100%' }}
                      value={Array.isArray(val) ? (val as string[]) : []}
                      onChange={(v) => updateRow(ri, 'condition', ci, v.length ? v : null)}
                      placeholder="-"
                    />
                  );
                }
                if (isRange) {
                  const pair = Array.isArray(val) ? (val as (number | null)[]) : [null, null];
                  const setBound = (idx: 0 | 1, v: number | null) => {
                    const next: (number | null)[] = [pair[0] ?? null, pair[1] ?? null];
                    next[idx] = v;
                    updateRow(ri, 'condition', ci, next[0] == null && next[1] == null ? null : next);
                  };
                  return (
                    <div style={{ display: 'flex', alignItems: 'center', gap: 2 }}>
                      <InputNumber
                        size="small"
                        style={{ width: '50%' }}
                        value={pair[0] as number ?? null}
                        onChange={(v) => setBound(0, v as number | null)}
                        placeholder="lo"
                      />
                      <InputNumber
                        size="small"
                        style={{ width: '50%' }}
                        value={pair[1] as number ?? null}
                        onChange={(v) => setBound(1, v as number | null)}
                        placeholder="hi"
                      />
                    </div>
                  );
                }
                return (
                  <Input
                    size="small"
                    style={{ width: '100%' }}
                    value={val != null ? String(val) : ''}
                    onChange={(e) => updateRow(ri, 'condition', ci, e.target.value || null)}
                    placeholder="-"
                  />
                );
              }}
            />
          );
        })}

        <Table.Column
          title={t('editor.decisionTable.decisionCode')}
          width={70}
          dataIndex="_decisionCode"
          render={(val: string, _: unknown, ri: number) => (
            <Select
              size="small"
              showSearch
              style={{ width: '100%' }}
              value={val || undefined}
              onChange={(v) => updateRow(ri, 'decisionCode', 0, v)}
              options={decisions}
              placeholder={t('editor.decisionTable.decisionPlaceholder')}
            />
          )}
        />

        <Table.Column
          title=""
          width={36}
          render={(_: unknown, __: unknown, ri: number) => (
            <Popconfirm title={t('editor.decisionTable.deleteRowConfirm')} onConfirm={() => removeRow(ri)}>
              <Button type="text" size="small" danger icon={<DeleteOutlined />} />
            </Popconfirm>
          )}
        />
      </Table>
    </div>
  );
}
