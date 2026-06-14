import { useEffect, useState } from 'react';
import { Button, Select, Input, Table, Popconfirm, Typography } from 'antd';
import { PlusOutlined, DeleteOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { useTenantStore } from '@/store/tenantStore';
import { listDecisions } from '@/api/decision';
import type { DecisionTableNode, DecisionTableColumn, DecisionTableRow, ConditionTypeMeta, MetricDescriptor } from '@/types';

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
      setDecisions((d.data ?? []).map((item) => ({ value: item.code, label: `${item.code} (${item.name})` })));
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
    .map((ct) => ({ value: ct.code, label: ct.displayName }));

  const addColumn = () => {
    const newCols = [...cols, { metricCode: '', operator: 'EQ', dataType: null }];
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
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 12 }}>
        <Typography.Text strong>{t('editor.decisionTable.title')}</Typography.Text>
        <div style={{ display: 'flex', gap: 8 }}>
          <Button size="small" icon={<PlusOutlined />} onClick={addColumn}>{t('editor.decisionTable.addColumn')}</Button>
          <Button size="small" icon={<PlusOutlined />} onClick={addRow}>{t('editor.decisionTable.addRow')}</Button>
        </div>
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
        scroll={{ x: 'max-content' }}
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
          return (
            <Table.Column
              key={ci}
              width={290}
              dataIndex={`_c${ci}`}
              title={(
                <div style={{ display: 'flex', alignItems: 'center', gap: 4 }}>
                  <Select
                    size="small"
                    style={{ width: 90 }}
                    value={col.valueRef ?? 'METRIC'}
                    onChange={(ref) => updateColumn(ci, 'valueRef', ref)}
                    options={[
                      { value: 'METRIC', label: '指标' },
                      { value: 'PAYLOAD', label: 'Payload' },
                    ]}
                  />
                  <Select
                    size="small"
                    showSearch
                    style={{ width: 130 }}
                    value={col.metricCode || undefined}
                    onChange={(v) => updateColumn(ci, 'metricCode', v)}
                    placeholder={t('editor.decisionTable.metric')}
                    popupMatchSelectWidth={false}
                    options={fieldOptions}
                  />
                  <Select
                    size="small"
                    style={{ width: 72 }}
                    value={col.operator || undefined}
                    onChange={(v) => updateColumn(ci, 'operator', v)}
                    options={opOptions}
                  />
                  <Button type="text" size="small" danger icon={<DeleteOutlined />}
                    onClick={() => removeColumn(ci)} />
                </div>
              )}
              render={(val: unknown, _: unknown, ri: number) => (
                <Input
                  size="small"
                  value={val != null ? String(val) : ''}
                  onChange={(e) => updateRow(ri, 'condition', ci, e.target.value || null)}
                  placeholder="-"
                />
              )}
            />
          );
        })}

        <Table.Column
          title={t('editor.decisionTable.decisionCode')}
          width={200}
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
