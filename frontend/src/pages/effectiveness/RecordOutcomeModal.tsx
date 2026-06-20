import { useState } from 'react';
import { Modal, Form, Input, InputNumber, DatePicker, Button, Space, message } from 'antd';
import { MinusCircleOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import type { Dayjs } from 'dayjs';
import { recordOutcomes } from '@/api/effectiveness';
import type { OutcomeItem } from '@/types';

interface FormOutcomeRow {
  eventId: string;
  outcomeLabel: string;
  outcomeValue?: number;
  labeledAt: Dayjs;
  source?: string;
  note?: string;
}

interface Props {
  open: boolean;
  tenantId: number;
  onClose: () => void;
}

/** 手工回灌结果标签 Modal（运维补录 / 演示用，轻量校验）。 */
export default function RecordOutcomeModal({ open, tenantId, onClose }: Props) {
  const { t } = useTranslation('effectiveness');
  const tc = useTranslation('common').t;
  const [form] = Form.useForm<{ rows: FormOutcomeRow[] }>();
  const [submitting, setSubmitting] = useState(false);

  const handleOk = async () => {
    const values = await form.validateFields();
    const outcomes: OutcomeItem[] = values.rows.map((r) => ({
      eventId: r.eventId,
      outcomeLabel: r.outcomeLabel,
      outcomeValue: r.outcomeValue,
      labeledAt: r.labeledAt.toISOString(),
      source: r.source || undefined,
      note: r.note || undefined,
    }));
    setSubmitting(true);
    try {
      const { accepted } = await recordOutcomes({ tenantId, outcomes });
      message.success(t('modal.accepted', { count: accepted }));
      form.resetFields();
      onClose();
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <Modal
      title={t('modal.title')}
      open={open}
      onCancel={onClose}
      onOk={handleOk}
      confirmLoading={submitting}
      okText={t('modal.submit')}
      cancelText={t('modal.cancel')}
      width={920}
      destroyOnClose
    >
      <div style={{ marginBottom: 12 }}>
        {t('modal.tenant')}: <strong>{tenantId}</strong>
      </div>
      <Form form={form} layout="vertical" initialValues={{ rows: [{}] }} preserve={false}>
        <Form.List name="rows">
          {(fields, { add, remove }) => (
            <>
              {fields.map((field) => (
                <Space key={field.key} align="baseline" style={{ display: 'flex', marginBottom: 8 }} wrap>
                  <Form.Item
                    name={[field.name, 'eventId']}
                    rules={[{ required: true, message: tc('validation.required') }]}
                    style={{ marginBottom: 0 }}
                  >
                    <Input placeholder={t('modal.eventId')} style={{ width: 200 }} />
                  </Form.Item>
                  <Form.Item
                    name={[field.name, 'outcomeLabel']}
                    rules={[{ required: true, message: tc('validation.required') }]}
                    style={{ marginBottom: 0 }}
                  >
                    <Input placeholder={t('modal.outcomeLabel')} style={{ width: 140 }} />
                  </Form.Item>
                  <Form.Item name={[field.name, 'outcomeValue']} style={{ marginBottom: 0 }}>
                    <InputNumber placeholder={t('modal.outcomeValue')} style={{ width: 110 }} />
                  </Form.Item>
                  <Form.Item
                    name={[field.name, 'labeledAt']}
                    rules={[{ required: true, message: tc('validation.required') }]}
                    style={{ marginBottom: 0 }}
                  >
                    <DatePicker showTime placeholder={t('modal.labeledAt')} style={{ width: 180 }} />
                  </Form.Item>
                  <Form.Item name={[field.name, 'source']} style={{ marginBottom: 0 }}>
                    <Input placeholder={t('modal.source')} style={{ width: 110 }} />
                  </Form.Item>
                  <Form.Item name={[field.name, 'note']} style={{ marginBottom: 0 }}>
                    <Input placeholder={t('modal.note')} style={{ width: 140 }} />
                  </Form.Item>
                  {fields.length > 1 && (
                    <MinusCircleOutlined onClick={() => remove(field.name)} />
                  )}
                </Space>
              ))}
              <Button type="dashed" onClick={() => add()} block>
                {t('modal.addRow')}
              </Button>
            </>
          )}
        </Form.List>
      </Form>
    </Modal>
  );
}
