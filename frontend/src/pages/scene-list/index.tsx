import { useEffect, useState } from 'react';
import { Table, Button, Modal, Form, Input, Select, message } from 'antd';
import { PlusOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { useTenantStore } from '@/store/tenantStore';
import { listScenes, createScene } from '@/api/scene';
import { SCENE_COLUMNS } from '@/config/columns/scene';
import { ROUTES, route } from '@/constants/routes';
import { DOMINANT_MODE_OPTIONS } from '@/constants/enums';
import type { SceneListItem } from '@/types';

export default function SceneList() {
  const navigate = useNavigate();
  const { currentId } = useTenantStore();
  const [scenes, setScenes] = useState<SceneListItem[]>([]);
  const [loading, setLoading] = useState(false);
  const [modalOpen, setModalOpen] = useState(false);
  const [confirmLoading, setConfirmLoading] = useState(false);
  const [form] = Form.useForm();

  const load = async () => {
    if (!currentId) return;
    setLoading(true);
    try {
      const data = await listScenes(currentId);
      setScenes(data.data ?? []);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { load(); }, [currentId]);

  const handleCreate = async () => {
    try {
      const values = await form.validateFields();
      setConfirmLoading(true);
      await createScene({ ...values, tenantId: currentId });
      message.success('Scene 创建成功');
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
        <h2>Scene 列表</h2>
        <Button type="primary" icon={<PlusOutlined />} onClick={() => setModalOpen(true)}>
          新建 Scene
        </Button>
      </div>
      <Table
        columns={SCENE_COLUMNS}
        dataSource={scenes}
        rowKey="id"
        loading={loading}
        onRow={(record) => ({
          onClick: () => navigate(route(ROUTES.SCENE_DETAIL, { sceneCode: record.sceneCode })),
          style: { cursor: 'pointer' },
        })}
      />
      <Modal
        title="新建 Scene"
        open={modalOpen}
        onOk={handleCreate}
        onCancel={() => { setModalOpen(false); form.resetFields(); }}
        confirmLoading={confirmLoading}
      >
        <Form form={form} layout="vertical">
          <Form.Item name="code" label="Scene Code" rules={[{ required: true, message: '请输入 Scene Code' }]}>
            <Input placeholder="如 risk.transfer" />
          </Form.Item>
          <Form.Item name="name" label="名称" rules={[{ required: true, message: '请输入名称' }]}>
            <Input />
          </Form.Item>
          <Form.Item name="dominantMode" label="使用模式" initialValue="PUSH">
            <Select options={[...DOMINANT_MODE_OPTIONS]} />
          </Form.Item>
          <Form.Item name="subjectType" label="主体类型" initialValue="USER">
            <Select options={[{ value: 'USER', label: 'USER' }]} />
          </Form.Item>
          <Form.Item name="description" label="说明">
            <Input.TextArea rows={2} />
          </Form.Item>
        </Form>
      </Modal>
    </>
  );
}
