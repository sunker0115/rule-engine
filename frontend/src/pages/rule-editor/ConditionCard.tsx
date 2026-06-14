import { useMemo, useCallback } from 'react';
import { Card, Select, Input, InputNumber, Button } from 'antd';
import { DeleteOutlined } from '@ant-design/icons';
import type { ConditionNode, ConditionTypeMeta, MetricDescriptor } from '@/types';
import { paramLabel, paramWidget } from '@/utils/param-registry';

interface Props {
  node: ConditionNode;
  conditionTypes: ConditionTypeMeta[];
  availableMetrics: MetricDescriptor[];
  onChange: (node: ConditionNode) => void;
  onDelete: () => void;
}

/** 按 conditionType code 查元数据 */
function findMeta(types: ConditionTypeMeta[], code: string): ConditionTypeMeta | undefined {
  return types.find(t => t.code === code);
}

export default function ConditionCard({
  node, conditionTypes, availableMetrics, onChange, onDelete,
}: Props) {
  const meta = useMemo(() => findMeta(conditionTypes, node.conditionType), [conditionTypes, node.conditionType]);
  const requiresMetric = meta?.requiresMetric ?? true;

  const setParam = useCallback((key: string, value: unknown) => {
    onChange({ ...node, params: { ...node.params, [key]: value } });
  }, [node, onChange]);

  /** 按已注册的参数控件类型动态渲染 */
  const renderParamField = (key: string) => {
    const widget = paramWidget(key);
    const label = paramLabel(key);
    const val = node.params[key];

    switch (widget) {
      case 'number':
        return (
          <div key={key} style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
            <span style={{ fontSize: 12, color: '#999', whiteSpace: 'nowrap' }}>{label}</span>
            <InputNumber
              size="small"
              style={{ width: 100 }}
              value={val as number | undefined}
              onChange={(v) => setParam(key, v)}
            />
          </div>
        );
      case 'array':
        return (
          <div key={key} style={{ display: 'flex', alignItems: 'center', gap: 6, flex: 1 }}>
            <span style={{ fontSize: 12, color: '#999', whiteSpace: 'nowrap' }}>{label}</span>
            <Select
              mode="tags"
              size="small"
              style={{ minWidth: 120, flex: 1 }}
              value={Array.isArray(val) ? val as string[] : []}
              onChange={(v) => setParam(key, v)}
              placeholder={label}
            />
          </div>
        );
      case 'operator-select':
        return (
          <div key={key} style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
            <span style={{ fontSize: 12, color: '#999', whiteSpace: 'nowrap' }}>{label}</span>
            <Select
              size="small"
              style={{ width: 100 }}
              value={val as string}
              onChange={(v) => setParam(key, v)}
              options={[
                { value: 'BEFORE', label: '早于' },
                { value: 'AFTER', label: '晚于' },
                { value: 'BETWEEN', label: '在…之间' },
              ]}
            />
          </div>
        );
      default:
        return (
          <div key={key} style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
            <span style={{ fontSize: 12, color: '#999', whiteSpace: 'nowrap' }}>{label}</span>
            <Input
              size="small"
              style={{ width: 100 }}
              value={val as string ?? ''}
              onChange={(e) => setParam(key, e.target.value)}
            />
          </div>
        );
    }
  };

  const requiredKeys = meta?.requiredParamKeys ?? Object.keys(node.params);

  const valueRefColor = node.valueRef === 'PAYLOAD' ? '#fa8c16' : '#1890ff';

  return (
    <Card
      size="small"
      style={{
        marginBottom: 4,
        borderLeft: `3px solid ${valueRefColor}`,
      }}
      styles={{ body: { padding: '8px 12px' } }}
    >
      {/* 头部：条件类型 + valueRef 标签 + 删除 */}
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 6 }}>
        <Select
          size="small"
          style={{ width: 160 }}
          value={node.conditionType || undefined}
          onChange={(code) => {
            const newMeta = findMeta(conditionTypes, code);
            const defaultParams: Record<string, unknown> = {};
            if (newMeta) {
              for (const k of newMeta.requiredParamKeys) defaultParams[k] = undefined;
            }
            onChange({ ...node, conditionType: code, params: defaultParams });
          }}
          options={conditionTypes.map((ct) => ({ value: ct.code, label: ct.displayName }))}
          placeholder="选择条件类型"
        />
        {requiresMetric && (
          <Select
            size="small"
            style={{ width: 80 }}
            value={node.valueRef ?? 'METRIC'}
            onChange={(ref) => onChange({ ...node, valueRef: ref as 'METRIC' | 'PAYLOAD' })}
            options={[
              { value: 'METRIC', label: '指标' },
              { value: 'PAYLOAD', label: 'Payload' },
            ]}
          />
        )}
        <div style={{ flex: 1 }} />
        <Button type="text" size="small" icon={<DeleteOutlined />} danger onClick={onDelete} />
      </div>

      {/* 参数区域 */}
      <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8, alignItems: 'center' }}>
        {requiresMetric && node.valueRef !== 'PAYLOAD' && (
          <Select
            size="small"
            showSearch
            style={{ minWidth: 200 }}
            value={node.metricCode || undefined}
            onChange={(code) => onChange({ ...node, metricCode: code })}
            placeholder="选择指标"
            popupMatchSelectWidth={false}
            options={availableMetrics.map((m) => ({ value: m.metricCode, label: m.metricCode }))}
          />
        )}
        {node.valueRef === 'PAYLOAD' && (
          <Input
            size="small"
            style={{ width: 120 }}
            value={node.metricCode ?? ''}
            onChange={(e) => onChange({ ...node, metricCode: e.target.value })}
            placeholder="payload 字段名"
          />
        )}
        {requiredKeys.map(renderParamField)}
      </div>
    </Card>
  );
}
