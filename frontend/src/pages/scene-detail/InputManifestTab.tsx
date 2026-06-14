import { useEffect, useState } from 'react';
import { Table, Select, Typography, Alert, Spin } from 'antd';
import { getInputManifest } from '@/api/inputManifest';
import type { InputFieldItem } from '@/types';
import type { ColumnsType } from 'antd/es/table';

interface Props {
  sceneCode: string;
  tenantCode: string;
  eventTypes: string[];
}

export default function InputManifestTab({ sceneCode, tenantCode, eventTypes }: Props) {
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

  useEffect(() => { load(); }, [eventType, sceneCode, tenantCode]);

  const columns: ColumnsType<InputFieldItem> = [
    { title: '字段名', dataIndex: 'name', key: 'name' },
    { title: '类型', dataIndex: 'dataType', key: 'dataType' },
    {
      title: '必填',
      dataIndex: 'required',
      key: 'required',
      render: (v: boolean) => v ? <span style={{ color: 'red' }}>必填</span> : '可选',
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
        message="调用方对该场景发评估请求时，payload 需包含以下字段"
        style={{ marginBottom: 16 }}
      />
      <div style={{ marginBottom: 16 }}>
        <span style={{ marginRight: 8 }}>按事件类型筛选：</span>
        <Select
          allowClear
          placeholder="全部事件类型"
          value={eventType}
          onChange={setEventType}
          options={(eventTypes ?? []).map((et) => ({ value: et, label: et }))}
          style={{ width: 240 }}
        />
      </div>
      <Spin spinning={loading}>
        <Table columns={columns} dataSource={fields} rowKey="name" pagination={false} size="small" />
      </Spin>
      {fields.length > 0 && (
        <div style={{ marginTop: 16 }}>
          <Typography.Title level={5}>请求体 payload 示例</Typography.Title>
          <pre style={{ background: '#f5f5f5', padding: 16, borderRadius: 4, overflow: 'auto' }}>
            {exampleJson}
          </pre>
        </div>
      )}
    </div>
  );
}
