import { useEffect, useState } from 'react';
import { Table, Select, Typography, Alert, Spin } from 'antd';
import { useTranslation } from 'react-i18next';
import { getInputManifest } from '@/api/inputManifest';
import type { InputFieldItem } from '@/types';
import type { ColumnsType } from 'antd/es/table';

interface Props {
  sceneCode: string;
  tenantCode: string;
  eventTypes: string[];
}

export default function InputManifestTab({ sceneCode, tenantCode, eventTypes }: Props) {
  const { t } = useTranslation('scene');
  const [fields, setFields] = useState<InputFieldItem[]>([]);
  const [loading, setLoading] = useState(false);
  const [eventType, setEventType] = useState<string | undefined>(undefined);

  const load = async () => {
    setLoading(true);
    try {
      const data = await getInputManifest(tenantCode, sceneCode, eventType);
      setFields(data.data?.fields ?? []);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { if (eventType) load(); else setFields([]); }, [eventType, sceneCode, tenantCode]);

  const columns: ColumnsType<InputFieldItem> = [
    { title: t('inputManifest.column.name'), dataIndex: 'name', key: 'name' },
    { title: t('inputManifest.column.dataType'), dataIndex: 'dataType', key: 'dataType' },
    {
      title: t('inputManifest.column.required'),
      dataIndex: 'required',
      key: 'required',
      render: (v: boolean) => v ? <span style={{ color: 'red' }}>{t('inputManifest.required')}</span> : t('inputManifest.optional'),
    },
  ];

  const exampleJson = JSON.stringify(
    Object.fromEntries(fields.map((f) => [f.name, f.dataType === 'STRING' ? '""' : f.dataType === 'BOOLEAN' ? false : 0])),
    null,
    2,
  );

  return (
    <div>
      <Alert
        type="info"
        message={t('inputManifest.info')}
        style={{ marginBottom: 16 }}
      />
      <div style={{ marginBottom: 16 }}>
        <span style={{ marginRight: 8 }}>{t('inputManifest.filterEventType')}：</span>
        <Select
          allowClear
          placeholder={t('inputManifest.filterAll')}
          value={eventType}
          onChange={setEventType}
          options={(eventTypes ?? []).map((et) => ({ value: et, label: et }))}
          style={{ width: 240 }}
        />
      </div>
      {!eventType ? (
        <Alert type="warning" message="请先选择事件类型" style={{ marginTop: 8 }} />
      ) : (
        <Spin spinning={loading}>
          <Table columns={columns} dataSource={fields} rowKey="name" pagination={false} size="small" />
        </Spin>
      )}
      {fields.length > 0 && (
        <div style={{ marginTop: 16 }}>
          <Typography.Title level={5}>{t('inputManifest.exampleTitle')}</Typography.Title>
          <pre style={{ background: '#f5f5f5', padding: 16, borderRadius: 4, overflow: 'auto' }}>
            {exampleJson}
          </pre>
        </div>
      )}
    </div>
  );
}
