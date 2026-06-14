import { useEffect, useState } from 'react';
import { useParams } from 'react-router-dom';
import { Layout, Spin } from 'antd';
import { useTranslation } from 'react-i18next';
import { useTenantStore } from '@/store/tenantStore';
import { useRuleStore } from '@/store/ruleStore';
import { getRule } from '@/api/rule';
import { getSceneMetadata } from '@/api/metadata';
import type { RuleDetail as RuleDetailType, SceneMetadata as SceneMetadataType } from '@/types';
import LeftPanel from './LeftPanel';
import CenterPanel from './CenterPanel';
import RightPanel from './RightPanel';

const { Sider, Content } = Layout;

export default function RuleEditor() {
  const { ruleId } = useParams<{ ruleId: string }>();
  const { currentId } = useTenantStore();
  const { t } = useTranslation('rule');
  const { loadFromDetail } = useRuleStore();
  const [loading, setLoading] = useState(true);
  const [ruleDetail, setRuleDetail] = useState<RuleDetailType | null>(null);
  const [metadata, setMetadata] = useState<SceneMetadataType | null>(null);

  useEffect(() => {
    if (!currentId || !ruleId) return;
    (async () => {
      setLoading(true);
      try {
        const detailRes = await getRule(currentId, Number(ruleId));
        const detail = detailRes.data;
        if (detail) {
          setRuleDetail(detail);
          loadFromDetail(
            detail.conditionAst ?? null,
            detail.decisionBindings ?? [],
            detail.preGates ?? [],
            detail.triggerEventTypes ?? [],
            detail.kind,
          );
          const metaRes = await getSceneMetadata(currentId, detail.sceneCode);
          setMetadata(metaRes.data ?? null);
        }
      } finally {
        setLoading(false);
      }
    })();
  }, [currentId, ruleId]);

  if (loading) return <Spin size="large" style={{ display: 'block', margin: '100px auto' }} />;
  if (!ruleDetail) return <div>{t('editor.leftPanel.ruleInfo')} not found</div>;

  return (
    <Layout style={{ background: '#fff', height: 'calc(100vh - 64px - 48px)' }}>
      <Sider width={260} style={{ background: '#fafafa', borderRight: '1px solid #f0f0f0', overflow: 'auto' }}>
        <LeftPanel ruleDetail={ruleDetail} />
      </Sider>
      <Content style={{ overflow: 'auto', padding: 16 }}>
        <CenterPanel metadata={metadata} />
      </Content>
      <Sider width={360} style={{ background: '#fafafa', borderLeft: '1px solid #f0f0f0', overflow: 'auto' }}>
        <RightPanel metadata={metadata} ruleDetail={ruleDetail} />
      </Sider>
    </Layout>
  );
}
