import { useState, useEffect } from 'react';
import { Drawer, Form, Input, Select, Button, Typography, message, Tag, Row, Col } from 'antd';
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
  ruleId?: number;
  sceneCode: string;
  eventTypes: string[];
}

interface PayloadPair {
  id: number;
  key: string;
  value: string;
}

let nextPairId = 0;

export default function DryRunDrawer({ open, onClose, ruleVersionId, ruleId, sceneCode, eventTypes }: Props) {
  const te = useTranslation('eval').t;
  const tc = useTranslation('common').t;
  const { current } = useTenantStore(); // tenant code, e.g. "loadtest"
  const { result, loading, setResult, setLoading, reset } = useDryRunStore();
  const [form] = Form.useForm();
  const [internalLoading, setInternalLoading] = useState(false);
  const [pairs, setPairs] = useState<PayloadPair[]>([]);

  const isLoading = loading || internalLoading;

  // 每次打开重置
  useEffect(() => {
    if (open) {
      setPairs([]);
      nextPairId = 0;
      form.resetFields();
      reset();
    }
  }, [open, form, setResult]);

  const addPair = () => {
    setPairs(p => [...p, { id: nextPairId++, key: '', value: '' }]);
  };

  const removePair = (id: number) => {
    setPairs(p => p.filter(item => item.id !== id));
  };

  const updatePair = (id: number, field: 'key' | 'value', val: string) => {
    setPairs(p => p.map(item => item.id === id ? { ...item, [field]: val } : item));
  };

  /** 值类型推断：简单数字→number，true/false→boolean，null→null，其余→string */
  const toPayloadValue = (raw: string): unknown => {
    const v = raw.trim();
    if (v === 'true') return true;
    if (v === 'false') return false;
    if (v === 'null') return null;
    if (/^-?\d+(\.\d+)?$/.test(v)) return parseFloat(v);
    return v;
  };

  const buildRequestBody = async () => {
    const values = await form.validateFields();
    const payload: Record<string, unknown> = {};
    pairs.forEach(p => {
      if (p.key.trim()) {
        payload[p.key.trim()] = toPayloadValue(p.value);
      }
    });
    return {
      tenantCode: current ?? '',
      sceneCode,
      eventType: values.eventType,
      subjectId: values.subjectId,
      eventId: `dry-${Date.now()}`,
      occurredAt: new Date().toISOString(),
      payload,
      queryParams: { ruleVersionId, ruleId },
    };
  };

  const handleCopyJson = async () => {
    try {
      const body = await buildRequestBody();
      await navigator.clipboard.writeText(JSON.stringify(body, null, 2));
      message.success('已复制请求 JSON');
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
          payload[p.key.trim()] = toPayloadValue(p.value);
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
      <Form form={form} layout="vertical">
        <Form.Item name="eventType" label={te('dryRun.eventType')} rules={[{ required: eventTypes.length > 0 }]}>
          {eventTypes.length > 0
            ? <Select options={eventTypes.map((e) => ({ value: e, label: e }))} />
            : <Input placeholder={te('dryRun.eventTypeAny')} />}
        </Form.Item>
        <Form.Item name="subjectId" label={te('dryRun.subjectId')} rules={[{ required: true }]}>
          <Input />
        </Form.Item>
        <Form.Item label={te('dryRun.payload')}>
          {pairs.length === 0 && (
            <Typography.Text type="secondary" style={{ display: 'block', marginBottom: 8 }}>
              {te('dryRun.payloadHint')}
            </Typography.Text>
          )}
          {pairs.map(p => (
            <Row gutter={8} key={p.id} style={{ marginBottom: 8 }}>
              <Col flex="auto">
                <Input
                  placeholder="key"
                  value={p.key}
                  onChange={e => updatePair(p.id, 'key', e.target.value)}
                />
              </Col>
              <Col flex="auto">
                <Input
                  placeholder="value"
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
          <Button type="primary" onClick={handleExecute} loading={isLoading} style={{ flex: 1 }}>
            {te('dryRun.execute')}
          </Button>
          <Button icon={<CopyOutlined />} onClick={handleCopyJson}>复制 JSON</Button>
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
