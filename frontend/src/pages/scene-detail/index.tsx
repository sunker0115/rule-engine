import { useEffect, useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { Tabs, Spin, Button } from 'antd';
import { ArrowLeftOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { useTenantStore } from '@/store/tenantStore';
import { getScene } from '@/api/scene';
import { ROUTES, route } from '@/constants/routes';
import type { SceneDetail as SceneDetailType } from '@/types';
import SceneInfo from './SceneInfo';
import InputManifestTab from './InputManifestTab';

export default function SceneDetail() {
  const { sceneCode } = useParams<{ sceneCode: string }>();
  const navigate = useNavigate();
  const { t } = useTranslation('scene');
  const tc = useTranslation('common').t;
  const { currentId, current } = useTenantStore();
  const [scene, setScene] = useState<SceneDetailType | null>(null);
  const [loading, setLoading] = useState(true);

  const load = async () => {
    if (!currentId || !sceneCode) return;
    setLoading(true);
    try {
      const data = await getScene(currentId, sceneCode);
      setScene(data.data ?? null);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { load(); }, [currentId, sceneCode]);

  if (loading) return <Spin size="large" style={{ display: 'block', margin: '100px auto' }} />;
  if (!scene) return <div>{t('detail.notFound')}</div>;

  const tabItems = [
    {
      key: 'info',
      label: t('detail.basicInfo'),
      children: <SceneInfo scene={scene} />,
    },
    {
      key: 'manifest',
      label: t('detail.inputManifest'),
      children: <InputManifestTab sceneCode={scene.sceneCode} tenantCode={current ?? ''} eventTypes={scene.eventTypes} />,
    },
  ];

  return (
    <div>
      <div style={{ marginBottom: 16, display: 'flex', alignItems: 'center', gap: 16 }}>
        <Button icon={<ArrowLeftOutlined />} onClick={() => navigate(ROUTES.SCENES)}>{tc('button.back')}</Button>
        <h2 style={{ margin: 0 }}>{scene.name} ({scene.sceneCode})</h2>
      </div>
      <Tabs
        items={tabItems}
        // “规则”页签是该场景规则列表（SCENE_RULES）的入口——切到该页签即跳整页规则列表
        tabBarExtraContent={
          <Button type="link" onClick={() => navigate(route(ROUTES.SCENE_RULES, { sceneCode: scene.sceneCode }))}>
            {t('detail.ruleList')}
          </Button>
        }
      />
    </div>
  );
}
