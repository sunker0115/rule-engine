import { useEffect, useState, useMemo } from 'react';
import { Table, Space, Input, Select, DatePicker, Button, Modal, Form, message } from 'antd';
import { SearchOutlined, PlusOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { useTenantStore } from '@/store/tenantStore';
import { listRules, createRule } from '@/api/rule';
import { getSceneMetadata } from '@/api/metadata';
import { listScenes } from '@/api/scene';
import { getRuleColumns } from '@/config/columns/rule';
import { RULE_STATUS_OPTIONS, RULE_KIND_OPTIONS } from '@/constants/enums';
import RuleDetailDrawer from '@/pages/rule-list/RuleDetailDrawer';
import dayjs from 'dayjs';
import type { RuleListItem } from '@/types';

const { RangePicker } = DatePicker;

async function fetchDefaultMetric(tenantId: number, sceneCode: string): Promise<string> {
  try {
    const res = await getSceneMetadata(tenantId, sceneCode);
    return res.data?.availableMetrics?.[0]?.metricCode ?? '';
  } catch {
    return '';
  }
}

export default function RulesAll() {
  const { currentId, activeList } = useTenantStore();
  const { t } = useTranslation('rule');
  const tc = useTranslation('common').t;
  const [rules, setRules] = useState<RuleListItem[]>([]);
  const [loading, setLoading] = useState(false);
  const [keyword, setKeyword] = useState('');
  const [statusFilter, setStatusFilter] = useState<string | undefined>(undefined);
  const [dateRange, setDateRange] = useState<[dayjs.Dayjs | null, dayjs.Dayjs | null] | null>(null);
  const [tenantFilter, setTenantFilter] = useState<number | undefined>(undefined);
  const [detailId, setDetailId] = useState<number | null>(null);
  const [createOpen, setCreateOpen] = useState(false);
  const [createLoading, setCreateLoading] = useState(false);
  const [sceneOpts, setSceneOpts] = useState<{ value: string; label: string }[]>([]);
  const [createForm] = Form.useForm();
  const formKind = Form.useWatch('kind', createForm) || 'AST_BOOLEAN';
  const tenantId = tenantFilter ?? currentId ?? 0;

  const load = async () => {
    if (!tenantId) return;
    setLoading(true);
    try {
      const params: Record<string, unknown> = {};
      if (statusFilter) params.status = statusFilter;
      if (dateRange?.[0]) params.from = dateRange[0].format('YYYY-MM-DD');
      if (dateRange?.[1]) params.to = dateRange[1].format('YYYY-MM-DD');
      const data = await listRules(tenantId, undefined, params);
      setRules(data.items ?? []);
    } finally { setLoading(false); }
  };

  useEffect(() => { load(); }, [tenantId, statusFilter, dateRange]);

  const dataSource = useMemo(() => {
    if (!keyword.trim()) return rules;
    const kw = keyword.toLowerCase();
    return rules.filter((r) => r.name.toLowerCase().includes(kw) || r.code.toLowerCase().includes(kw));
  }, [rules, keyword]);

  const handleCreate = async () => {
    const values = await createForm.validateFields();
    setCreateLoading(true);
    try {
      const body: Record<string, unknown> = { ...values, tenantId: values.tenantId ?? currentId! };
      if (values.kind === 'SCORECARD') {
        body.conditionAst = { type: 'ScorecardRootNode', conditions: [], threshold: 0 };
      } else if (values.kind === 'DECISION_TREE') {
        body.conditionAst = { type: 'IfNode', condition: { type: 'AndNode', children: [] }, thenBranch: { type: 'DecisionLeafNode', decisionCode: '', category: null }, elseBranch: null };
      } else if (values.kind === 'DECISION_TABLE') {
        const defaultMetric = await fetchDefaultMetric(values.tenantId ?? currentId!, values.sceneCode);
        body.conditionAst = { type: 'DecisionTableNode', columns: [{ metricCode: defaultMetric, operator: 'EQ', dataType: null }], rows: [{ conditions: [null], decisionCode: '' }] };
      }
      if (values.kind === 'EXPRESSION_SCRIPT') {
        body.script = { lang: values.scriptLang || 'CEL', source: values.scriptSource || '{true}' };
      }
      await createRule(values.tenantId ?? currentId!, body);
      message.success(tc('message.createSuccess'));
      setCreateOpen(false);
      createForm.resetFields();
      load();
    } catch { /* handled by interceptor */ }
    finally { setCreateLoading(false); }
  };

  return (
    <div>
      <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 16 }}>
        <h2>{t('title.list')}</h2>
        <Button type="primary" icon={<PlusOutlined />} onClick={async () => {
          createForm.resetFields();
          setCreateOpen(true);
          try {
            const apiRes = await listScenes(tenantId);
            const list = apiRes.data ?? [];
            setSceneOpts(list.map((s) => ({ value: s.sceneCode, label: `${s.name} (${s.sceneCode})` })));
          } catch { setSceneOpts([]); }
        }}>
          {t('action.create')}
        </Button>
      </div>
      <Space style={{ marginBottom: 16 }}>
        <Select
          placeholder={tc('tenant.placeholder')}
          value={tenantFilter}
          onChange={setTenantFilter}
          allowClear
          options={activeList.map((t) => ({ value: t.id, label: `${t.name} (${t.code})` }))}
          style={{ width: 180 }}
        />
        <Input
          prefix={<SearchOutlined />}
          placeholder={t('searchPlaceholder')}
          value={keyword}
          onChange={(e) => setKeyword(e.target.value)}
          allowClear
          style={{ width: 240 }}
        />
        <Select
          placeholder={tc('label.status')}
          value={statusFilter}
          onChange={setStatusFilter}
          allowClear
          options={[...RULE_STATUS_OPTIONS]}
          style={{ width: 130 }}
        />
        <RangePicker
          value={dateRange as [dayjs.Dayjs, dayjs.Dayjs] | null}
          onChange={(dates) => setDateRange(dates as [dayjs.Dayjs | null, dayjs.Dayjs | null] | null)}
          placeholder={[t('filter.dateFrom'), t('filter.dateTo')]}
          style={{ width: 260 }}
        />
      </Space>
      <Table
        columns={getRuleColumns(t, tc, setDetailId)}
        dataSource={dataSource}
        rowKey="ruleDefinitionId"
        loading={loading}
      />
      <RuleDetailDrawer
        open={detailId !== null}
        ruleDefinitionId={detailId}
        onClose={() => setDetailId(null)}
      />
      <Modal
        title={t('action.create')}
        open={createOpen}
        onOk={handleCreate}
        onCancel={() => { setCreateOpen(false); createForm.resetFields(); }}
        confirmLoading={createLoading}
      >
        <Form form={createForm} layout="vertical">
          <Form.Item name="tenantId" label={tc('tenant.label')} initialValue={currentId}>
            <Select
              options={activeList.map((t) => ({ value: t.id, label: `${t.name} (${t.code})` }))}
            />
          </Form.Item>
          <Form.Item name="sceneCode" label="Scene" rules={[{ required: true, message: tc('validation.required') }]}>
            <Select
              options={sceneOpts}
              showSearch
              placeholder="选择场景"
              onChange={(sceneCode) => createForm.setFieldValue('sceneCode', sceneCode)}
            />
          </Form.Item>
          <Form.Item name="code" label="Code" rules={[{ required: true, message: tc('validation.required') }]}>
            <Input />
          </Form.Item>
          <Form.Item name="name" label={tc('label.name')} rules={[{ required: true, message: tc('validation.required') }]}>
            <Input />
          </Form.Item>
          <Form.Item name="kind" label={t('column.kind')} initialValue="AST_BOOLEAN">
            <Select options={[...RULE_KIND_OPTIONS]} />
          </Form.Item>
          {formKind === 'EXPRESSION_SCRIPT' && (
            <>
              <Form.Item name="scriptLang" label="脚本语言" initialValue="CEL" rules={[{ required: true }]}>
                <Select options={[
                  { value: 'CEL', label: 'CEL' },
                  { value: 'Aviator', label: 'Aviator' },
                  { value: 'QLExpress', label: 'QLExpress' },
                  { value: 'JsonLogic', label: 'JsonLogic' },
                  { value: 'JEXL', label: 'JEXL' },
                  { value: 'Groovy', label: 'Groovy' },
                ]} />
              </Form.Item>
              <Form.Item name="scriptSource" label="脚本源码" initialValue="{true}" rules={[{ required: true, message: tc('validation.required') }]}>
                <Input.TextArea rows={6} placeholder='例如: metrics.amount > 1000' />
              </Form.Item>
            </>
          )}
          <Form.Item name="triggerEventTypes" label="Trigger Events">
            <Select mode="tags" placeholder={t('triggerEventsPlaceholder')} />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
}
