import { useEffect, useState, useMemo } from 'react';
import { Table, Button, Modal, Form, Input, Select, message, Space } from 'antd';
import { PlusOutlined, SearchOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { useTenantStore } from '@/store/tenantStore';
import { listScenes, createScene } from '@/api/scene';
import { SCENE_COLUMNS } from '@/config/columns/scene';
import { ROUTES, route } from '@/constants/routes';
import { STATUS_OPTIONS } from '@/constants/enums';
import type { SceneListItem } from '@/types';

export default function SceneList() {
  const navigate = useNavigate();
  const { t } = useTranslation('scene');
  const tc = useTranslation('common').t;
  const { currentId, activeList } = useTenantStore();
  const [scenes, setScenes] = useState<SceneListItem[]>([]);
  const [loading, setLoading] = useState(false);
  const [keyword, setKeyword] = useState('');
  const [statusFilter, setStatusFilter] = useState<string | undefined>(undefined);
  const [tenantFilter, setTenantFilter] = useState<number | undefined>(undefined);

  const tenantId = tenantFilter ?? currentId ?? 0;
  const [modalOpen, setModalOpen] = useState(false);
  const [confirmLoading, setConfirmLoading] = useState(false);
  const [form] = Form.useForm();

  const load = async () => {
    if (!tenantId) return;
    setLoading(true);
    try {
      const params: Record<string, unknown> = {};
      if (statusFilter) params.status = statusFilter;
      const data = await listScenes(tenantId, params);
      setScenes(data.data ?? []);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { load(); }, [tenantId, statusFilter]);

  const dataSource = useMemo(() => {
    if (!keyword.trim()) return scenes;
    const kw = keyword.toLowerCase();
    return scenes.filter((s) => s.sceneCode.toLowerCase().includes(kw) || s.name.toLowerCase().includes(kw));
  }, [scenes, keyword]);

  const handleCreate = async () => {
    try {
      const values = await form.validateFields();
      setConfirmLoading(true);
      await createScene({ ...values, tenantId: currentId });
      message.success(tc('message.createSuccess'));
      setModalOpen(false);
      form.resetFields();
      load();
    } catch {
      // validation failed or API error, handled by interceptor
    } finally {
      setConfirmLoading(false);
    }
  };

  return (
    <>
      <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 16 }}>
        <h2>{t('title.list')}</h2>
        <Button type="primary" icon={<PlusOutlined />} onClick={() => setModalOpen(true)}>
          {t('action.create')}
        </Button>
      </div>
      <Space style={{ marginBottom: 16 }}>
        <Select
          placeholder="租户"
          value={tenantFilter}
          onChange={setTenantFilter}
          allowClear
          options={activeList.map((t) => ({ value: t.id, label: `${t.name} (${t.code})` }))}
          style={{ width: 200 }}
        />
        <Select
          placeholder={tc('label.status')}
          value={statusFilter}
          onChange={setStatusFilter}
          allowClear
          options={[...STATUS_OPTIONS]}
          style={{ width: 130 }}
        />
        <Input
          prefix={<SearchOutlined />}
          placeholder="搜索名称或 Code"
          value={keyword}
          onChange={(e) => setKeyword(e.target.value)}
          allowClear
          style={{ width: 240 }}
        />
      </Space>
      <Table
        columns={SCENE_COLUMNS}
        dataSource={dataSource}
        rowKey="id"
        loading={loading}
        onRow={(record) => ({
          onClick: () => navigate(route(ROUTES.SCENE_DETAIL, { sceneCode: record.sceneCode })),
          style: { cursor: 'pointer' },
        })}
      />
      <Modal
        title={t('action.create')}
        open={modalOpen}
        onOk={handleCreate}
        onCancel={() => { setModalOpen(false); form.resetFields(); }}
        confirmLoading={confirmLoading}
      >
        <Form form={form} layout="vertical">
          <Form.Item name="code" label={t('form.code')} rules={[{ required: true, message: tc('validation.required') }]}>
            <Input placeholder={t('form.codePlaceholder')} />
          </Form.Item>
          <Form.Item name="name" label={t('form.name')} rules={[{ required: true, message: tc('validation.required') }]}>
            <Input />
          </Form.Item>
          <Form.Item name="dominantMode" label={t('form.dominantMode')} initialValue="PUSH">
            <Select options={[...DOMINANT_MODE_OPTIONS]} />
          </Form.Item>
          <Form.Item name="subjectType" label={t('form.subjectType')} initialValue="USER">
            <Select options={[{ value: 'USER', label: 'USER' }]} />
          </Form.Item>
          <Form.Item name="description" label={t('form.description')}>
            <Input.TextArea rows={2} />
          </Form.Item>
        </Form>
      </Modal>
    </>
  );
}
