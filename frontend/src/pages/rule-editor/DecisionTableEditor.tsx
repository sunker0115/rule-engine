import { useEffect, useState } from 'react';
import { Button, Select, Input, Table, Popconfirm, Typography } from 'antd';
import { PlusOutlined, DeleteOutlined } from '@ant-design/icons';
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
  const { currentId } = useTenantStore();
  const [decisions, setDecisions] = useState<{ value: string; label: string }[]>([]);

  useEffect(() => {
    if (currentId) listDecisions(currentId).then((d) => {
      setDecisions((d.data ?? []).map((item) => ({ value: item.code, label: `${item.code} (${item.name})` })));
    });
  }, [currentId]);

  const columns: DecisionTableColumn[] = node.columns ?? [];
  const rows: DecisionTableRow[] = node.rows ?? [];

  // 空表或空 metricCode 自动预填：取第一个可用 metric 或 payload 字段
  useEffect(() => {
    const firstMetric = availableMetrics[0]?.metricCode || payloadFieldNames[0] || '';
    if (!firstMetric) return; // 没有任何可用来源，无法预填

    if (columns.length === 0) {
      onChange({
        ...node,
        columns: [{ metricCode: firstMetric, operator: 'EQ', dataType: null }],
        rows: [{ conditions: [null], decisionCode: '' }],
      });
      return;
    }

    // 补填空 metricCode 的列
    const fixed = columns.map((c) =>
      c.metricCode ? c : { ...c, metricCode: firstMetric },
    );
    if (JSON.stringify(fixed) !== JSON.stringify(columns)) {
      onChange({ ...node, columns: fixed });
    }
  }, []);

  // 决策表列仅支持基础比较算子
  const TABLE_OPERATORS = ['EQ', 'NEQ', 'GT', 'GTE', 'LT', 'LTE', 'IN', 'NOT_IN', 'BETWEEN', 'NOT_BETWEEN'];
  const opOptions = conditionTypes
    .filter((ct) => TABLE_OPERATORS.includes(ct.code))
    .map((ct) => ({ value: ct.code, label: ct.displayName }));

  // ===== 列操作 =====
  const addColumn = () => {
    const newCols = [...columns, { metricCode: '', operator: 'EQ', dataType: null }];
    // 每行补一个 null
    const newRows = rows.map((r) => ({ ...r, conditions: [...r.conditions, null] }));
    onChange({ ...node, columns: newCols, rows: newRows });
  };

  const updateColumn = (colIndex: number, field: keyof DecisionTableColumn, value: string) => {
    const newCols = columns.map((c, i) => (i === colIndex ? { ...c, [field]: value } : c));
    onChange({ ...node, columns: newCols });
  };

  const removeColumn = (colIndex: number) => {
    if (columns.length <= 1) return; // 至少保留一列
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

  // ===== 数据源渲染 =====
  const dataSource = rows.map((r, ri) => {
    const base: Record<string, unknown> = { _key: ri, _decisionCode: r.decisionCode };
    columns.forEach((_, ci) => { base[`_c${ci}`] = r.conditions[ci]; });
    return base;
  });

  return (
    <div style={{ padding: 8 }}>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 16 }}>
        <Typography.Text strong>决策表</Typography.Text>
        <div style={{ display: 'flex', gap: 8 }}>
          <Button size="small" icon={<PlusOutlined />} onClick={addColumn}>加列</Button>
          <Button size="small" icon={<PlusOutlined />} onClick={addRow}>加行</Button>
        </div>
      </div>

      <Table
          dataSource={dataSource}
          rowKey="_key"
          size="small"
          pagination={false}
          scroll={{ x: 'max-content' }}
          locale={{ emptyText: '暂无数据行，点击「加行」添加' }}
          footer={() => (
            <Button type="dashed" size="small" icon={<PlusOutlined />} onClick={addRow} block>加行</Button>
          )}
        >
          {/* 列头：每列 metricCode + operator */}
          {columns.map((col, ci) => (
            <Table.Column
              key={ci}
              title={(
                <div style={{ display: 'flex', flexDirection: 'column', gap: 2, minWidth: 160 }}>
                  <Select
                    size="small"
                    showSearch
                    style={{ width: '100%' }}
                    value={col.metricCode || undefined}
                    onChange={(v) => updateColumn(ci, 'metricCode', v)}
                    placeholder="指标"
                    options={availableMetrics.map((m) => ({ value: m.metricCode, label: m.metricCode }))}
                  />
                  <Select
                    size="small"
                    style={{ width: '100%' }}
                    value={col.operator || undefined}
                    onChange={(v) => updateColumn(ci, 'operator', v)}
                    options={opOptions}
                  />
                  <Button type="text" size="small" danger icon={<DeleteOutlined />}
                    onClick={() => removeColumn(ci)} style={{ fontSize: 11 }}>删列</Button>
                </div>
              )}
              dataIndex={`_c${ci}`}
              render={(val: unknown, _: unknown, ri: number) => (
                <Input
                  size="small"
                  style={{ minWidth: 100 }}
                  value={val != null ? String(val) : ''}
                  onChange={(e) => updateRow(ri, 'condition', ci, e.target.value || null)}
                  placeholder="值或留空(通配)"
                />
              )}
            />
          ))}
          {/* 决策码列 */}
          <Table.Column
            title="决策码"
            dataIndex="_decisionCode"
            render={(val: string, _: unknown, ri: number) => (
              <Select
                size="small"
                showSearch
                style={{ minWidth: 150 }}
                value={val || undefined}
                onChange={(v) => updateRow(ri, 'decisionCode', 0, v)}
                options={decisions}
                placeholder="Decision"
              />
            )}
          />
          {/* 操作列 */}
          <Table.Column
            title=""
            width={40}
            render={(_: unknown, __: unknown, ri: number) => (
              <Popconfirm title="删除此行?" onConfirm={() => removeRow(ri)}>
                <Button type="text" size="small" danger icon={<DeleteOutlined />} />
              </Popconfirm>
            )}
          />
        </Table>
    </div>
  );
}
