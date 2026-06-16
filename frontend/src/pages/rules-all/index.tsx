import { useEffect, useState } from 'react';
import { Table, Space, Input, Select, DatePicker, Button, Modal, Form, message, Empty } from 'antd';
import { PlusOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { useTenantStore } from '@/store/tenantStore';
import { listRules, createRule } from '@/api/rule';
import { getSceneMetadata } from '@/api/metadata';
import { listScenes } from '@/api/scene';
import { ROUTES, route } from '@/constants/routes';
import { getRuleColumns } from '@/config/columns/rule';
import { getRuleStatusOptions, getRuleKindOptions } from '@/constants/enums';
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
  const navigate = useNavigate();
  const { currentId, activeList } = useTenantStore();
  const { t } = useTranslation('rule');
  const tc = useTranslation('common').t;
  const [rules, setRules] = useState<RuleListItem[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(20);
  const [loading, setLoading] = useState(false);
  const [statusFilter, setStatusFilter] = useState<string | undefined>(undefined);
  const [dateRange, setDateRange] = useState<[dayjs.Dayjs | null, dayjs.Dayjs | null] | null>(null);
  const [tenantFilter, setTenantFilter] = useState<number | undefined>(undefined);
  const [detailId, setDetailId] = useState<number | null>(null);
  const [createOpen, setCreateOpen] = useState(false);
  const [createLoading, setCreateLoading] = useState(false);
  const [sceneOpts, setSceneOpts] = useState<{ value: string; label: string }[]>([]);
  const [createForm] = Form.useForm();
  const [formKind, setFormKind] = useState('AST_BOOLEAN');
  const [langOptions, setLangOptions] = useState<{ value: string; label: string }[]>(
    () => [{ value: 'CEL', label: 'CEL' }],
  );
  const tenantId = tenantFilter ?? currentId ?? 0;

  const load = async () => {
    if (!tenantId) return;
    setLoading(true);
    try {
      // 筛选（status/from/to）与分页全部交后端处理，不再做客户端过滤
      const params: Record<string, unknown> = { page, size: pageSize };
      if (statusFilter) params.status = statusFilter;
      if (dateRange?.[0]) params.from = dateRange[0].format('YYYY-MM-DD');
      if (dateRange?.[1]) params.to = dateRange[1].format('YYYY-MM-DD');
      const data = await listRules(tenantId, undefined, params);
      setRules(data.items ?? []);
      setTotal(data.total ?? 0);
    } finally { setLoading(false); }
  };

  useEffect(() => { load(); }, [tenantId, page, pageSize, statusFilter, dateRange]);

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
      const created = await createRule(values.tenantId ?? currentId!, body);
      message.success(tc('message.createSuccess'));
      setCreateOpen(false);
      createForm.resetFields();
      // 新建草稿后直接跳编辑器继续配置（避免列表→再点进去的断点）
      navigate(route(ROUTES.RULE_EDITOR, { ruleId: created.ruleDefinitionId }));
    } catch { /* handled by interceptor */ }
    finally { setCreateLoading(false); }
  };

  return (
    <div>
      <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 16 }}>
        <h2>{t('title.list')}</h2>
        <Button type="primary" icon={<PlusOutlined />} onClick={async () => {
          createForm.resetFields();
          setFormKind('AST_BOOLEAN');
          setCreateOpen(true);
          // 拉取引擎语言列表（需任一 sceneCode，取第一个）
          try {
            const sRes = await listScenes(tenantId);
            const scenes = sRes.data ?? [];
            if (scenes.length > 0) {
              const meta = await getSceneMetadata(tenantId, scenes[0].sceneCode);
              const langs = meta.data?.expressionLangs ?? ['CEL'];
              setLangOptions(langs.map((l: string) => ({ value: l, label: l })));
            }
          } catch { /* keep default */ }
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
          onChange={(v) => { setTenantFilter(v); setPage(1); }}
          allowClear
          options={activeList.map((t) => ({ value: t.id, label: `${t.name} (${t.code})` }))}
          style={{ width: 180 }}
        />
        <Select
          placeholder={tc('label.status')}
          value={statusFilter}
          onChange={(v) => { setStatusFilter(v); setPage(1); }}
          allowClear
          options={getRuleStatusOptions(t)}
          style={{ width: 130 }}
        />
        <RangePicker
          value={dateRange as [dayjs.Dayjs, dayjs.Dayjs] | null}
          onChange={(dates) => { setDateRange(dates as [dayjs.Dayjs | null, dayjs.Dayjs | null] | null); setPage(1); }}
          placeholder={[t('filter.dateFrom'), t('filter.dateTo')]}
          style={{ width: 260 }}
        />
      </Space>
      {tenantId ? (
        <Table
          columns={getRuleColumns(t, tc, setDetailId)}
          dataSource={rules}
          rowKey="ruleDefinitionId"
          loading={loading}
          scroll={{ x: 'max-content', y: 'calc(100vh - 312px)' }}
          pagination={{
            current: page,
            pageSize,
            total,
            showSizeChanger: true,
            showTotal: (t) => tc('label.paginationTotal', { total: t }),
            onChange: (p, ps) => { setPage(p); setPageSize(ps); },
          }}
        />
      ) : (
        <Empty description={tc('tenant.notSelected')} style={{ marginTop: 80 }}>
          <Button type="primary" onClick={() => navigate(ROUTES.TENANTS)}>{tc('tenant.goSelect')}</Button>
        </Empty>
      )}
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
          <Form.Item name="tenantId" label={tc('label.tenant')} rules={[{ required: true }]} initialValue={currentId}>
            <Select
              options={activeList.map((t) => ({ value: t.id, label: `${t.name} (${t.code})` }))}
            />
          </Form.Item>
          <Form.Item name="sceneCode" label={t('editor.createModal.scene')} rules={[{ required: true, message: tc('validation.required') }]}>
            <Select
              options={sceneOpts}
              showSearch
              placeholder={t('editor.createModal.scenePlaceholder')}
              onChange={(sceneCode) => createForm.setFieldValue('sceneCode', sceneCode)}
            />
          </Form.Item>
          <Form.Item name="code" label={t('editor.createModal.code')} rules={[{ required: true, message: tc('validation.required') }]}>
            <Input />
          </Form.Item>
          <Form.Item name="name" label={t('editor.createModal.name')} rules={[{ required: true, message: tc('validation.required') }]}>
            <Input />
          </Form.Item>
          <Form.Item name="kind" label={t('column.kind')} initialValue="AST_BOOLEAN">
            <Select options={getRuleKindOptions(t)} onChange={(v) => setFormKind(v)} />
          </Form.Item>
          {formKind === 'EXPRESSION_SCRIPT' && (
            <>
              <Form.Item name="scriptLang" label={t('editor.createModal.scriptLang')} initialValue={langOptions[0]?.value} rules={[{ required: true }]}>
                <Select options={langOptions} />
              </Form.Item>
              <Form.Item name="scriptSource" label={t('editor.createModal.scriptSource')} initialValue={t('editor.createModal.scriptSourceDefault')} rules={[{ required: true, message: tc('validation.required') }]}>
                <Input.TextArea rows={6} placeholder={t('editor.createModal.scriptSourcePlaceholder')} />
              </Form.Item>
            </>
          )}
          <Form.Item name="triggerEventTypes" label={t('editor.createModal.triggerEvents')}>
            <Select mode="tags" placeholder={t('editor.createModal.triggerEventsPlaceholder')} />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
}
