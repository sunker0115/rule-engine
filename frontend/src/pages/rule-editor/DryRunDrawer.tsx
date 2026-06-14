import { useState } from 'react';
import { Drawer, Form, Input, Select, Button, Typography, message, Tag } from 'antd';
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

export default function DryRunDrawer({ open, onClose, ruleVersionId, ruleId, sceneCode, eventTypes }: Props) {
  const te = useTranslation('eval').t;
  const tc = useTranslation('common').t;
  const { current } = useTenantStore(); // tenant code, e.g. "loadtest"
  const { result, loading, setResult, setLoading } = useDryRunStore();
  const [form] = Form.useForm();
  const [internalLoading, setInternalLoading] = useState(false);

  const isLoading = loading || internalLoading;

  const handleExecute = async () => {
    const values = await form.validateFields();
    setInternalLoading(true);
    setLoading(true);
    try {
      const payload = values.payload ? JSON.parse(values.payload) : {};
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
        <Form.Item name="eventType" label={te('dryRun.eventType')} rules={[{ required: true }]}>
          <Select options={eventTypes.map((e) => ({ value: e, label: e }))} />
        </Form.Item>
        <Form.Item name="subjectId" label={te('dryRun.subjectId')} rules={[{ required: true }]}>
          <Input />
        </Form.Item>
        <Form.Item name="payload" label={te('dryRun.payload')}>
          <Input.TextArea rows={6} style={{ fontFamily: 'monospace' }} placeholder='{"amount": 100}' />
        </Form.Item>
        <Button type="primary" onClick={handleExecute} loading={isLoading} block>
          {te('dryRun.execute')}
        </Button>
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
