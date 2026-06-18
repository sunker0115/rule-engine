import { useEffect, useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { Descriptions, Button, Tabs, Spin, message, Form, Input, InputNumber, Tag, Modal, Space, Typography } from 'antd';
import { ArrowLeftOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { useTenantStore } from '@/store/tenantStore';
import { getDecision, updateDecision, getDecisionSources } from '@/api/decision';
import { ROUTES } from '@/constants/routes';
import { colorOf, getStatusOptions } from '@/constants/enums';
import { formatDateTime } from '@/utils/format';
import LineageTable from '@/components/lineage/LineageTable';
import { useLineage } from '@/components/lineage/useLineage';
import type { DecisionItem } from '@/types';

export default function DecisionDetail() {
  const { code } = useParams<{ code: string }>();
  const navigate = useNavigate();
  const { t } = useTranslation('decision');
  const tc = useTranslation('common').t;
  const tl = useTranslation('lineage').t;
  const { currentId } = useTenantStore();
  const [decision, setDecision] = useState<DecisionItem | null>(null);
  const [loading, setLoading] = useState(true);
  const [editing, setEditing] = useState(false);
  const [saving, setSaving] = useState(false);
  const [form] = Form.useForm();
  const { loading: sourcesLoading, rows: sources, load: loadSources } = useLineage(getDecisionSources);

  const load = async () => {
    if (!currentId || !code) return;
    setLoading(true);
    try {
      const data = await getDecision(currentId, code);
      setDecision(data ?? null);
    } finally { setLoading(false); }
  };

  useEffect(() => { load(); }, [currentId, code]);

  const doSave = async (values: Record<string, unknown>) => {
    setSaving(true);
    try {
      await updateDecision(currentId!, code!, values);
      message.success(tc('message.saveSuccess'));
      setEditing(false);
      load();
    } finally { setSaving(false); }
  };

  // 停用拦截：仍被规则产出时二次确认，列出产出规则；启用无需拦截
  const handleToggleStatus = async () => {
    if (!decision || !currentId || !code) return;
    const enabling = decision.status !== 'ACTIVE';
    if (enabling) {
      await updateDecision(currentId, code, { status: 'ACTIVE' });
      message.success(tc('message.enabled'));
      load();
      return;
    }
    const res = await getDecisionSources(currentId, code);
    const doDisable = async () => {
      await updateDecision(currentId, code, { status: 'DISABLED' });
      message.success(tc('message.disabled'));
      load();
    };
    if (res.sourceCount > 0) {
      Modal.confirm({
        title: tl('disableGuardTitle'),
        content: (
          <div>
            {res.sources.map((s) => (
              <div key={s.ruleDefinitionId} style={{ fontFamily: 'ui-monospace, Menlo, monospace' }}>{s.ruleCode}</div>
            ))}
          </div>
        ),
        okText: tl('disableGuardConfirm'),
        cancelText: tc('button.cancel'),
        onOk: doDisable,
      });
    } else {
      doDisable();
    }
  };

  if (loading) return <Spin size="large" style={{ display: 'block', margin: '100px auto' }} />;
  if (!decision) return <div>{t('detail.notFound')}</div>;

  const statusColor = colorOf(getStatusOptions(tc), decision.status ?? 'ACTIVE');
  const sourceCount = sources.length;

  const tabItems = [
    {
      key: 'info',
      label: t('detail.basicInfo'),
      children: editing ? (
        <Form form={form} layout="vertical">
          <Form.Item name="code" label={t('form.code')}><Input disabled /></Form.Item>
          <Form.Item name="name" label={t('form.name')} rules={[{ required: true }]}><Input /></Form.Item>
          <Form.Item name="priority" label={t('form.priority')} extra={t('form.priorityExtra')} rules={[{ required: true }]}>
            <InputNumber min={1} style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item name="description" label={t('form.description')}><Input.TextArea rows={2} /></Form.Item>
          <Space>
            <Button type="primary" onClick={() => form.validateFields().then(doSave)} loading={saving}>{tc('button.save')}</Button>
            <Button onClick={() => setEditing(false)}>{tc('button.cancel')}</Button>
          </Space>
        </Form>
      ) : (
        <div>
          <Descriptions bordered column={2} size="small">
            <Descriptions.Item label={t('form.code')}>{decision.code}</Descriptions.Item>
            <Descriptions.Item label={t('form.name')}>{decision.name}</Descriptions.Item>
            <Descriptions.Item label={t('form.priority')}>{decision.priority}</Descriptions.Item>
            <Descriptions.Item label={t('column.status')}><Tag color={statusColor}>{decision.status}</Tag></Descriptions.Item>
            <Descriptions.Item label={t('form.description')} span={2}>{decision.description || '-'}</Descriptions.Item>
            <Descriptions.Item label={tc('label.createdAt')}>{formatDateTime(decision.createdAt)}</Descriptions.Item>
            <Descriptions.Item label={tc('label.updatedAt')}>{formatDateTime(decision.updatedAt)}</Descriptions.Item>
          </Descriptions>
          <div style={{ marginTop: 16 }}>
            <Button type="primary" onClick={() => { form.setFieldsValue(decision); setEditing(true); }}>{tc('button.edit')}</Button>
          </div>
        </div>
      ),
    },
    {
      key: 'sources',
      label: `${t('detail.sources')}${sourceCount ? ` (${sourceCount})` : ''}`,
      children: <LineageTable rows={sources} loading={sourcesLoading} />,
    },
  ];

  return (
    <div>
      <div style={{ marginBottom: 16, display: 'flex', alignItems: 'center', gap: 16 }}>
        <Button icon={<ArrowLeftOutlined />} onClick={() => navigate(ROUTES.DECISIONS)}>{tc('button.back')}</Button>
        <Typography.Title level={4} style={{ margin: 0 }}>{decision.name} ({decision.code})</Typography.Title>
        <Tag color={statusColor}>{decision.status}</Tag>
        <Button style={{ marginLeft: 'auto' }} onClick={handleToggleStatus}>
          {decision.status === 'ACTIVE' ? t('action.disable') : t('action.enable')}
        </Button>
      </div>
      <Tabs
        items={tabItems}
        // 切到「被引用规则」Tab 才懒加载血缘
        onChange={(key) => { if (key === 'sources' && currentId && code) loadSources(currentId, code); }}
      />
    </div>
  );
}
