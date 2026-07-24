import { useState, useEffect, useRef } from 'react';
import { Drawer, Form, Input, Select, Button, Switch, Typography, message, Tag, Row, Col } from 'antd';
import { PlusOutlined, DeleteOutlined, CopyOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { useTenantStore } from '@/store/tenantStore';
import { useDryRunStore } from '@/store/dryRunStore';
import { dryRun } from '@/api/eval';
import type { NodeTraceItem } from '@/types';

interface Props {
  open: boolean;
  onClose: () => void;
  ruleVersionId?: number;
  /** 目标版本号，仅用于展示"正在试算 v{N}" */
  versionLabel?: number;
  ruleId?: number;
  sceneCode: string;
  eventTypes: string[];
  payloadFieldNames: string[];
  payloadFieldTypes?: Record<string, string>;
  /** 模板参数名（来自 script.params），试算时可选择并覆盖。 */
  paramKeys?: string[];
}

interface PayloadPair {
  id: number;
  key: string;
  value: string;
}

let nextPairId = 0;

export default function DryRunDrawer({ open, onClose, ruleVersionId, versionLabel, ruleId, sceneCode, eventTypes, payloadFieldNames, payloadFieldTypes, paramKeys }: Props) {
  const te = useTranslation('eval').t;
  const tc = useTranslation('common').t;
  const { current } = useTenantStore(); // tenant code, e.g. "loadtest"
  const { result, loading, autoRerun, setResult, setLoading, setAutoRerun } = useDryRunStore();
  const [form] = Form.useForm();
  const [internalLoading, setInternalLoading] = useState(false);
  const [pairs, setPairs] = useState<PayloadPair[]>([]);

  const isLoading = loading || internalLoading;
  const rerunTimer = useRef<ReturnType<typeof setTimeout>>();

  // 关闭时关自动重算和 loading（不清 showTrace，画布高亮保留）
  useEffect(() => {
    if (!open) { setAutoRerun(false); setLoading(false); }
  }, [open, setAutoRerun, setLoading]);

  // What-if 自动重算：监听输入变化 → 300ms 防抖调 API
  const [formValues, setFormValues] = useState<Record<string, unknown>>({});
  useEffect(() => {
    if (!autoRerun || !open) return;
    clearTimeout(rerunTimer.current);
    rerunTimer.current = setTimeout(() => {
      form.validateFields().then(() => handleExecute()).catch(() => {});
    }, 400);
    return () => clearTimeout(rerunTimer.current);
  }, [autoRerun, formValues, pairs, open]);

  const addPair = () => {
    setPairs(p => [...p, { id: nextPairId++, key: '', value: '' }]);
  };

  const removePair = (id: number) => {
    setPairs(p => p.filter(item => item.id !== id));
  };

  const updatePair = (id: number, field: 'key' | 'value', val: string) => {
    setPairs(p => p.map(item => item.id === id ? { ...item, [field]: val } : item));
  };


  /** 按场景声明的类型转换 payload 值 */
  const toPayloadValue = (key: string, raw: string): unknown => {
    const t = (payloadFieldTypes?.[key] ?? 'string').toLowerCase();
    if (t === 'integer' || t === 'long') return parseInt(raw, 10);
    if (t === 'number' || t === 'double' || t === 'decimal') return parseFloat(raw);
    if (t === 'boolean') return raw === 'true';
    return raw; // string / 未知类型保持原样
  };

  const buildRequestBody = async () => {
    const values = await form.validateFields();
    const payload: Record<string, unknown> = {};
    pairs.forEach(p => {
      if (p.key.trim()) {
        payload[p.key.trim()] = toPayloadValue(p.key.trim(), p.value);
      }
    });
    return {
      tenantCode: current ?? '',
      sceneCode,
      eventType: values.eventType || 'default',
      subjectId: values.subjectId,
      eventId: `dry-${Date.now()}`,
      occurredAt: new Date().toISOString(),
      payload,
    };
  };

  const handleCopyJson = async () => {
    try {
      const body = await buildRequestBody();
      await navigator.clipboard.writeText(JSON.stringify(body, null, 2));
      message.success(te('dryRun.copiedJson'));
    } catch { /* 表单校验不过时不复制 */ }
  };

  const handleExecute = async () => {
    const values = await form.validateFields();
    setInternalLoading(true);
    setLoading(true);
    try {
      const payload: Record<string, unknown> = {};
      pairs.forEach(p => {
        if (p.key.trim()) {
          payload[p.key.trim()] = toPayloadValue(p.key.trim(), p.value);
        }
      });
      const data = await dryRun(
        {
          tenantCode: current ?? '',   // eval API 用 tenantCode（字符串 code）
          sceneCode,
          eventType: values.eventType,
          subjectId: values.subjectId,
          eventId: `dry-${Date.now()}`,
          occurredAt: new Date().toISOString(),
          payload,
        },
        { ruleVersionId, ruleId },    // query param，不在 body
      );
      setResult(data);
    } catch {
      message.error(tc('message.loadError'));
    } finally {
      setInternalLoading(false);
      setLoading(false);
    }
  };

  const renderTraceNode = (node: NodeTraceItem, depth: number = 0): React.ReactNode => {
    const icon = node.result === true ? '✅' : node.result === false ? '❌' : '⏭';
    return (
      <div key={`${depth}-${node.nodeType}`} style={{ marginLeft: depth * 24, marginBottom: 4 }}>
        <span>{icon} </span>
        <Typography.Text>{node.nodeType}</Typography.Text>
        {node.conditionType && <Tag style={{ marginLeft: 8 }}>{node.conditionType}</Tag>}
        {node.metricCode && <Tag color="blue" style={{ marginLeft: 4 }}>{node.metricCode}</Tag>}
        {node.actualValue !== null && node.actualValue !== undefined && (
          <Typography.Text type="secondary" style={{ marginLeft: 8 }}>
            = {JSON.stringify(node.actualValue)}
          </Typography.Text>
        )}
        {node.valueSource && <Tag style={{ marginLeft: 4 }}>{node.valueSource}</Tag>}
        {node.errorCode && <Tag color="red" style={{ marginLeft: 4 }}>{node.errorCode}</Tag>}
        {node.children.map((child, i) => (
          <div key={i}>{renderTraceNode(child, depth + 1)}</div>
        ))}
      </div>
    );
  };

  return (
    <Drawer title={te('title.dryRun')} open={open} onClose={onClose} width={500}>
      {versionLabel !== undefined && (
        <div style={{ marginBottom: 16 }}>
          <Typography.Text type="secondary">{te('dryRun.targetVersion')}: </Typography.Text>
          <Tag color="blue">v{versionLabel}</Tag>
        </div>
      )}
      <Form form={form} layout="vertical" onValuesChange={(_, all) => setFormValues(all)}>
        <Form.Item name="eventType" label={te('dryRun.eventType')} initialValue="default" rules={[{ required: true }]}>
          <Select
            mode="tags"
            maxCount={1}
            placeholder={te('dryRun.eventTypeAny')}
            options={eventTypes.map((e) => ({ value: e, label: e }))}
            style={{ width: '100%' }}
          />
        </Form.Item>
        <Form.Item name="subjectId" label={te('dryRun.subjectId')} rules={[{ required: true }]}>
          <Input />
        </Form.Item>
        {(paramKeys?.length ?? 0) > 0 && (
          <Form.Item label="模板参数（已内嵌，无需手动添加）">
            {paramKeys!.map((k) => (
              <Tag key={k} style={{ marginBottom: 4 }}>{k}</Tag>
            ))}
          </Form.Item>
        )}

        <Form.Item label={te('dryRun.payload')}>
          {pairs.length === 0 && (
            <Typography.Text type="secondary" style={{ display: 'block', marginBottom: 8 }}>
              {te('dryRun.payloadHint')}
            </Typography.Text>
          )}
          {pairs.map(p => (
            <Row gutter={8} key={p.id} style={{ marginBottom: 8 }}>
              <Col flex="auto">
                {payloadFieldNames.length > 0 ? (
                  <Select
                    showSearch
                    value={p.key || undefined}
                    onChange={(val) => updatePair(p.id, 'key', val ?? '')}
                    placeholder={te('dryRun.field')}
                    options={payloadFieldNames.map((f) => ({ value: f, label: f }))}
                    allowClear
                    style={{ width: '100%' }}
                  />
                ) : (
                  <Input
                    placeholder={te('dryRun.key')}
                    value={p.key}
                    onChange={e => updatePair(p.id, 'key', e.target.value)}
                  />
                )}
              </Col>
              <Col flex="auto">
                <Input
                  placeholder={te('dryRun.value')}
                  value={p.value}
                  onChange={e => updatePair(p.id, 'value', e.target.value)}
                />
              </Col>
              <Col>
                <Button icon={<DeleteOutlined />} size="small" onClick={() => removePair(p.id)} />
              </Col>
            </Row>
          ))}
          <Button type="dashed" block icon={<PlusOutlined />} onClick={addPair}>
            {te('dryRun.addField')}
          </Button>
        </Form.Item>
        <div style={{ display: 'flex', gap: 8 }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 8, fontSize: 12, color: '#5b6672' }}>
            <Switch size="small" checked={autoRerun} onChange={setAutoRerun} />
            <span>{te('dryRun.autoRerun')}</span>
          </div>
          <div style={{ display: 'flex', gap: 8 }}>
            <Button type="primary" onClick={handleExecute} loading={isLoading} style={{ flex: 1 }}>
              {te('dryRun.execute')}
            </Button>
            <Button icon={<CopyOutlined />} onClick={handleCopyJson}>{te('dryRun.copyJson')}</Button>
          </div>
        </div>
      </Form>

      {result && (
        <div style={{ marginTop: 24 }}>
          <Typography.Title level={5}>
            {result.ruleHit ? (
              <Tag color="green">{te('dryRun.result.hit')}</Tag>
            ) : (
              <Tag color="orange">{te('dryRun.result.miss')}</Tag>
            )}
            {result.finalDecision && <span> → {result.finalDecision.code}</span>}
          </Typography.Title>
          <Typography.Title level={5}>{te('dryRun.result.nodeTrace')}</Typography.Title>
          {result.nodeTrace.map((n, i) => (
            <div key={i}>{renderTraceNode(n)}</div>
          ))}
        </div>
      )}
    </Drawer>
  );
}
