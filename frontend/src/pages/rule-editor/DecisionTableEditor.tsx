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

  const columns: DecisionTableColumn[] = node.columns ?? [];
  const rows: DecisionTableRow[] = node.rows ?? [];

  // 空表自动预填
  useEffect(() => {
    const firstMetric = availableMetrics[0]?.metricCode || payloadFieldNames[0] || '';
    if (!firstMetric) return;
    if (columns.length === 0) {
      onChange({
        ...node,
        columns: [{ metricCode: firstMetric, operator: 'EQ', dataType: null }],
        rows: [{ conditions: [null], decisionCode: '' }],
      });
      return;
    }
    const fixed = columns.map((c) =>
      c.metricCode ? c : { ...c, metricCode: firstMetric },
    );
    if (JSON.stringify(fixed) !== JSON.stringify(columns)) {
      onChange({ ...node, columns: fixed });
    }
  }, []);

  const TABLE_OPERATORS = ['EQ', 'NEQ', 'GT', 'GTE', 'LT', 'LTE', 'IN', 'NOT_IN', 'BETWEEN', 'NOT_BETWEEN'];
  const opOptions = conditionTypes
    .filter((ct) => TABLE_OPERATORS.includes(ct.code))
    .map((ct) => ({ value: ct.code, label: ct.displayName }));

  // ===== 列操作 =====
  const addColumn = () => {
    const newCols = [...columns, { metricCode: '', operator: 'EQ', dataType: null }];
    const newRows = rows.map((r) => ({ ...r, conditions: [...r.conditions, null] }));
    onChange({ ...node, columns: newCols, rows: newRows });
  };

  const updateColumn = (colIndex: number, field: keyof DecisionTableColumn, value: unknown) => {
    const newCols = columns.map((c, i) => (i === colIndex ? { ...c, [field]: value } : c));
    onChange({ ...node, columns: newCols });
  };

  const removeColumn = (colIndex: number) => {
    if (columns.length <= 1) return;
    const newCols = columns.filter((_, i) => i !== colIndex);
    const newRows = rows.map((r) => ({ ...r, conditions: r.conditions.filter((_, i) => i !== colIndex) }));
    onChange({ ...node, columns: newCols, rows: newRows });
  };

  // ===== 行操作 =====
  const addRow = () => {
    const newRow: DecisionTableRow = { conditions: columns.map(() => null), decisionCode: '' };
    onChange({ ...node, rows: [...rows, newRow] });
  };

  const updateRow = (rowIndex: number, field: 'decisionCode' | 'condition', colIndex: number, value: unknown) => {
    const newRows = rows.map((r, i) => {
      if (i !== rowIndex) return r;
      if (field === 'decisionCode') return { ...r, decisionCode: value as string };
      const newConds = [...r.conditions];
      newConds[colIndex] = value;
      return { ...r, conditions: newConds };
    });
    onChange({ ...node, rows: newRows });
  };

  const removeRow = (rowIndex: number) => {
    onChange({ ...node, rows: rows.filter((_, i) => i !== rowIndex) });
  };

  // ===== 数据源 =====
  const dataSource = rows.map((r, ri) => {
    const base: Record<string, unknown> = { _key: ri, _decisionCode: r.decisionCode };
    columns.forEach((_, ci) => { base[`_c${ci}`] = r.conditions[ci]; });
    return base;
  });

  return (
    <div style={{ padding: 8 }}>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 16 }}>
        <Typography.Text strong>{t('editor.decisionTable.title')}</Typography.Text>
        <div style={{ display: 'flex', gap: 8 }}>
          <Button size="small" icon={<PlusOutlined />} onClick={addColumn}>{t('editor.decisionTable.addColumn')}</Button>
          <Button size="small" icon={<PlusOutlined />} onClick={addRow}>{t('editor.decisionTable.addRow')}</Button>
        </div>
      </div>

      <Table
        dataSource={dataSource}
        rowKey="_key"
        size="small"
        pagination={false}
        scroll={{ x: 'max-content' }}
        locale={{ emptyText: t('editor.decisionTable.emptyRowHint') }}
        footer={() => (
          <Button type="dashed" size="small" icon={<PlusOutlined />} onClick={addRow} block>{t('editor.decisionTable.addRow')}</Button>
        )}
      >

        {/* 条件列 */}
        {columns.map((col, ci) => {
          const isPayload = col.valueRef === 'PAYLOAD';
          const fieldOptions = isPayload
            ? payloadFieldNames.map((f) => ({ value: f, label: f }))
            : availableMetrics.map((m) => ({ value: m.metricCode, label: m.metricCode }));
          return (
            <Table.Column
              key={ci}
              dataIndex={`_c${ci}`}
              title={(
                <div style={{ display: 'flex', flexDirection: 'column', gap: 3 }}>
                  {/* 第一行：取值源 + 列名 */}
                  <div style={{ display: 'flex', gap: 3 }}>
                    <Select
                      size="small"
                      style={{ width: 60 }}
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
                      style={{ width: 160 }}
                      value={col.metricCode || undefined}
                      onChange={(v) => updateColumn(ci, 'metricCode', v)}
                      placeholder={t('editor.decisionTable.metric')}
                      popupMatchSelectWidth={false}
                      options={fieldOptions}
                    />
                  </div>
                  {/* 第二行：算子 + 删除 */}
                  <div style={{ display: 'flex', gap: 3 }}>
                    <Select
                      size="small"
                      style={{ flex: 1 }}
                      value={col.operator || undefined}
                      onChange={(v) => updateColumn(ci, 'operator', v)}
                      options={opOptions}
                    />
                    <Button type="text" size="small" danger icon={<DeleteOutlined />}
                      onClick={() => removeColumn(ci)}
                      title={t('editor.decisionTable.deleteColumn')} />
                  </div>
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

        {/* 决策码列 */}
        <Table.Column
          title={t('editor.decisionTable.decisionCode')}
          dataIndex="_decisionCode"
          render={(val: string, _: unknown, ri: number) => (
            <Select
              size="small"
              showSearch
              style={{ width: 120 }}
              value={val || undefined}
              onChange={(v) => updateRow(ri, 'decisionCode', 0, v)}
              options={decisions}
              placeholder={t('editor.decisionTable.decisionPlaceholder')}
            />
          )}
        />

        {/* 删除行 */}
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
