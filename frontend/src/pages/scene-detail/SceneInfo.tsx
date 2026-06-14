import { Descriptions, Button, Tag, Space } from 'antd';
import { useTranslation } from 'react-i18next';
import { useNavigate } from 'react-router-dom';
import { ROUTES, route } from '@/constants/routes';
import type { SceneDetail as SceneDetailType } from '@/types';

interface Props {
  scene: SceneDetailType;
}

export default function SceneInfo({ scene }: Props) {
  const { t } = useTranslation('scene');
  const tc = useTranslation('common').t;
  const navigate = useNavigate();

  return (
    <div>
      <Descriptions bordered column={2} size="small">
        <Descriptions.Item label={t('form.code')}>{scene.sceneCode}</Descriptions.Item>
        <Descriptions.Item label={t('form.name')}>{scene.name}</Descriptions.Item>
        <Descriptions.Item label={t('form.dominantMode')}>{scene.dominantMode}</Descriptions.Item>
        <Descriptions.Item label={t('form.subjectType')}>{scene.subjectType}</Descriptions.Item>
        <Descriptions.Item label={t('form.decisionStrategy')}>{scene.decisionStrategy}</Descriptions.Item>
        <Descriptions.Item label={t('form.status')}><Tag>{scene.status}</Tag></Descriptions.Item>
        <Descriptions.Item label={t('form.description')} span={2}>{scene.description || '-'}</Descriptions.Item>
        <Descriptions.Item label={t('form.eventTypes')} span={2}>{(scene.eventTypes ?? []).join(', ') || '-'}</Descriptions.Item>
      </Descriptions>
      <div style={{ marginTop: 16 }}>
        <Space>
          <Button type="primary" onClick={() => navigate(route(ROUTES.SCENE_EDIT, { sceneCode: scene.sceneCode }))}>{tc('button.edit')}</Button>
        </Space>
      </div>
    </div>
  );
}
