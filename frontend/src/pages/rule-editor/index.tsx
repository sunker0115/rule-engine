import { useEffect, useState } from 'react';
import { useParams } from 'react-router-dom';
import { Layout, Spin } from 'antd';
import { MenuFoldOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { useTenantStore } from '@/store/tenantStore';
import { useRuleStore } from '@/store/ruleStore';
import { getRule } from '@/api/rule';
import { getSceneMetadata } from '@/api/metadata';
import { getScene } from '@/api/scene';
import type { RuleDetail as RuleDetailType, SceneMetadata as SceneMetadataType } from '@/types';
import LeftPanel from './LeftPanel';
import CenterPanel from './CenterPanel';
import RightPanel from './RightPanel';
import DryRunDrawer from './DryRunDrawer';

const { Sider, Content } = Layout;

/** 从 payloadSchema（数组或 JSON Schema）中提取字段名列表 */
function extractPayloadFieldNames(schema: unknown): string[] {
  if (!schema) return [];
  if (Array.isArray(schema)) {
    return schema.map((f: Record<string, unknown>) => f.name as string).filter(Boolean);
  }
  if (typeof schema === 'object') {
    const props = (schema as Record<string, unknown>).properties;
    if (props && typeof props === 'object') return Object.keys(props);
  }
  return [];
}

export default function RuleEditor() {
  const { ruleId } = useParams<{ ruleId: string }>();
  const { currentId } = useTenantStore();
  const { t } = useTranslation('rule');
  const { loadFromDetail } = useRuleStore();
  const [loading, setLoading] = useState(true);
  const [ruleDetail, setRuleDetail] = useState<RuleDetailType | null>(null);
  const [metadata, setMetadata] = useState<SceneMetadataType | null>(null);
  const [dryRunOpen, setDryRunOpen] = useState(false);
  const [rightCollapsed, setRightCollapsed] = useState(false);

  const load = async () => {
    if (!currentId || !ruleId) return;
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
        const [metaRes, sceneRes] = await Promise.all([
          getSceneMetadata(currentId, detail.sceneCode),
          getScene(currentId, detail.sceneCode),
        ]);
        const meta = metaRes.data ?? null;
        if (meta) {
          meta.payloadFieldNames = extractPayloadFieldNames(sceneRes.data?.payloadSchema);
        }
        setMetadata(meta);
      }
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { load(); }, [currentId, ruleId]);

  if (loading) return <Spin size="large" style={{ display: 'block', margin: '100px auto' }} />;
  if (!ruleDetail) return <div>{t('editor.notFound')}</div>;

  // 取最新 DRAFT 或 ACTIVE 版本 id 作为 dry-run 目标
  const latestVersion = ruleDetail.versions
    ?.filter(v => v.status === 'DRAFT' || v.status === 'ACTIVE')
    .sort((a, b) => b.version - a.version)[0];

  return (
    <Layout style={{ background: '#fff', height: 'calc(100vh - 64px - 48px)' }}>
      <Sider width={260} style={{ background: '#fafafa', borderRight: '1px solid #f0f0f0', overflow: 'auto' }}>
        <LeftPanel ruleDetail={ruleDetail} onOpenDryRun={() => setDryRunOpen(true)} onUpdated={load} />
      </Sider>
      <div style={{ display: 'flex', flex: 1, minHeight: 0 }}>
        <Content style={{ flex: 1, overflow: 'auto', padding: 16 }}>
          <CenterPanel metadata={metadata} />
        </Content>
        {/* 折叠/展开箭头：始终贴右边面板左边界垂直居中 */}
        <div
          onClick={() => setRightCollapsed(!rightCollapsed)}
          style={{
            width: 28, display: 'flex', alignItems: 'center', justifyContent: 'center',
            cursor: 'pointer', background: '#fff',
            paddingLeft: 4,
          }}
        >
          <MenuFoldOutlined
            style={{ fontSize: 12, color: '#999', transform: rightCollapsed ? 'rotate(180deg)' : undefined }}
          />
        </div>
        <div
          style={{
            width: rightCollapsed ? 0 : 360,
            background: '#fafafa', borderLeft: '1px solid #f0f0f0',
            overflow: rightCollapsed ? 'hidden' : 'auto',
            transition: 'width 0.2s',
          }}
        >
          <RightPanel metadata={metadata} ruleDetail={ruleDetail} />
        </div>
      </div>

      <DryRunDrawer
        open={dryRunOpen}
        onClose={() => setDryRunOpen(false)}
        ruleVersionId={latestVersion?.ruleVersionId}
        ruleId={ruleDetail.ruleDefinitionId}
        sceneCode={ruleDetail.sceneCode}
        eventTypes={metadata?.eventTypes ?? ruleDetail.triggerEventTypes ?? []}
      />
    </Layout>
  );
}
