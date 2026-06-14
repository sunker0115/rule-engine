import { Descriptions, Tag, Table } from 'antd';
import { useTranslation } from 'react-i18next';
import type { SceneDetail as SceneDetailType } from '@/types';

interface Props { scene: SceneDetailType; }

export default function SceneInfo({ scene }: Props) {
  const { t } = useTranslation('scene');

  const schemaFields = Array.isArray(scene.payloadSchema) ? scene.payloadSchema as Record<string, unknown>[] : [];
  const params = (scene.defaultParams && typeof scene.defaultParams === 'object'
    ? scene.defaultParams as Record<string, unknown>
    : {}) as Record<string, unknown>;
  const paramEntries = Object.entries(params);

  return (
    <div>
      <Descriptions bordered column={2} size="small" style={{ marginBottom: 16 }}>
        <Descriptions.Item label={t('form.code')}>{scene.sceneCode}</Descriptions.Item>
        <Descriptions.Item label={t('form.name')}>{scene.name}</Descriptions.Item>
        <Descriptions.Item label={t('form.dominantMode')}>{scene.dominantMode}</Descriptions.Item>
        <Descriptions.Item label={t('form.subjectType')}>{scene.subjectType}</Descriptions.Item>
        <Descriptions.Item label={t('form.decisionStrategy')}>{scene.decisionStrategy}</Descriptions.Item>
        <Descriptions.Item label={t('form.status')}><Tag>{scene.status}</Tag></Descriptions.Item>
        <Descriptions.Item label={t('form.description')} span={2}>{scene.description || '-'}</Descriptions.Item>
        <Descriptions.Item label={t('form.eventTypes')} span={2}>
          {(scene.eventTypes ?? []).join(', ') || '-'}
        </Descriptions.Item>
      </Descriptions>

      {schemaFields.length > 0 && (
        <div style={{ marginBottom: 16 }}>
          <h4>{t('form.payloadSchema')}</h4>
          <Table
            dataSource={schemaFields.map((f, i) => ({ ...f, _key: i }))}
            rowKey="_key"
            size="small"
            pagination={false}
            columns={[
              { title: '字段名', dataIndex: 'name', key: 'name' },
              { title: '类型', dataIndex: 'type', key: 'type', width: 100 },
              { title: '必填', dataIndex: 'required', key: 'required', width: 60, render: (v: boolean) => v ? '是' : '否' },
              { title: '敏感', dataIndex: 'sensitive', key: 'sensitive', width: 60, render: (v: boolean) => v ? '是' : '否' },
            ]}
          />
        </div>
      )}

      {paramEntries.length > 0 && (
        <div style={{ marginBottom: 16 }}>
          <h4>{t('form.defaultParams')}</h4>
          <Descriptions bordered column={2} size="small">
            {paramEntries.map(([k, v]) => (
              <Descriptions.Item key={k} label={k}>{String(v)}</Descriptions.Item>
            ))}
          </Descriptions>
        </div>
      )}
    </div>
  );
}
