import { useEffect, useState } from 'react';
import { useParams } from 'react-router-dom';
import { Layout, Spin } from 'antd';
import { MenuFoldOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { useTenantStore } from '@/store/tenantStore';
import { useRuleStore } from '@/store/ruleStore';
import { getRule } from '@/api/rule';
import { getSceneMetadata } from '@/api/metadata';
import { getScene, getAnalysis } from '@/api/scene';
import type { RuleDetail as RuleDetailType, SceneMetadata as SceneMetadataType, RuleVersionItem, RuleSetAnalysisReport } from '@/types';
import LeftPanel from './LeftPanel';
import CenterPanel from './CenterPanel';
import RightPanel from './RightPanel';
import DryRunDrawer from './DryRunDrawer';
import RuleAnalysisDrawer from './RuleAnalysisDrawer';
import { isAnalyzableKind } from './analysisSummary';

const { Sider, Content } = Layout;

/** 从 payloadSchema 提取字段名列表 + 类型映射 */
function extractPayloadSchema(schema: unknown): { names: string[]; types: Record<string, string> } {
  if (!schema) return { names: [], types: {} };
  const names: string[] = [];
  const types: Record<string, string> = {};
  if (Array.isArray(schema)) {
    for (const f of schema as Record<string, unknown>[]) {
      const n = f.name as string;
      if (n) { names.push(n); types[n] = (f.type as string) ?? 'string'; }
    }
  } else if (typeof schema === 'object') {
    const props = (schema as Record<string, unknown>).properties;
    if (props && typeof props === 'object') {
      for (const [n, def] of Object.entries(props)) {
        names.push(n);
        types[n] = (def as Record<string, unknown>).type as string ?? 'string';
      }
    }
  }
  return { names, types };
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
  // 当前要试算的目标版本；null 表示走默认（最新 DRAFT/ACTIVE 版本）
  const [dryRunTarget, setDryRunTarget] = useState<RuleVersionItem | null>(null);
  const [rightCollapsed, setRightCollapsed] = useState(false);
  const [analysisOpen, setAnalysisOpen] = useState(false);
  const [analysisReport, setAnalysisReport] = useState<RuleSetAnalysisReport | null>(null);
  const [analysisLoading, setAnalysisLoading] = useState(false);

  // 打开试算抽屉：传入 version 则针对该历史版本，否则默认最新版本
  const openDryRun = (version?: RuleVersionItem) => {
    setDryRunTarget(version ?? null);
    setDryRunOpen(true);
  };

  // 规则集分析：只读、按需拉取（tenantId 优先用规则自身的，未选全局时不至于传 0）
  const fetchAnalysis = async (sceneCode: string, tenantId: number) => {
    if (!tenantId) return;
    setAnalysisLoading(true);
    try {
      setAnalysisReport(await getAnalysis(sceneCode, tenantId));
    } catch {
      setAnalysisReport(null);
    } finally {
      setAnalysisLoading(false);
    }
  };

  const load = async () => {
    if (!currentId || !ruleId) return;
    setLoading(true);
    try {
      const detailRes = await getRule(currentId, Number(ruleId));
      const detail = detailRes;
      if (detail) {
        setRuleDetail(detail);
        loadFromDetail(
          detail.conditionAst ?? null,
          detail.decisionBindings ?? [],
          detail.preGates ?? [],
          detail.triggerEventTypes ?? [],
          detail.kind,
          detail.script ?? null,
        );
        const [metaRes, sceneRes] = await Promise.all([
          getSceneMetadata(currentId, detail.sceneCode),
          getScene(currentId, detail.sceneCode),
        ]);
        const meta = metaRes ?? null;
        if (meta) {
          const schema = extractPayloadSchema(sceneRes?.payloadSchema);
          meta.payloadFieldNames = schema.names;
          meta.payloadFieldTypes = schema.types;
        }
        setMetadata(meta);
      }
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { load(); }, [currentId, ruleId]);

  // 规则详情就绪后拉取规则集分析（用于左栏摘要条 + 按钮未读计数）；非可分析 kind 不拉取
  useEffect(() => {
    if (ruleDetail && isAnalyzableKind(ruleDetail.kind)) {
      fetchAnalysis(ruleDetail.sceneCode, Number(ruleDetail.tenantId) || currentId || 0);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [ruleDetail?.sceneCode, ruleDetail?.tenantId, ruleDetail?.kind]);

  // 定位 finding 对应规则：编辑器为单规则视图，关闭抽屉让用户看到当前规则编辑区与 badge
  const handleLocate = () => setAnalysisOpen(false);

  if (loading) return <Spin size="large" style={{ display: 'block', margin: '100px auto' }} />;
  if (!ruleDetail) return <div>{t('editor.notFound')}</div>;

  // 取最新 DRAFT 或 ACTIVE 版本作为默认 dry-run 目标
  const latestVersion = ruleDetail.versions
    ?.filter(v => v.status === 'DRAFT' || v.status === 'ACTIVE')
    .sort((a, b) => b.version - a.version)[0];
  // 实际试算的版本：选中的历史版本优先，否则回退默认最新版本
  const dryRunVersion = dryRunTarget ?? latestVersion;

  return (
    <Layout style={{ background: '#fff', height: 'calc(100vh - 64px - 48px)' }}>
      <Sider width={260} style={{ background: '#fafafa', borderRight: '1px solid #f0f0f0', overflow: 'auto' }}>
        <LeftPanel
          ruleDetail={ruleDetail}
          onOpenDryRun={openDryRun}
          onUpdated={load}
          analysisReport={analysisReport}
          onOpenAnalysis={() => setAnalysisOpen(true)}
        />
      </Sider>
      <div style={{ display: 'flex', flex: 1, minHeight: 0 }}>
        <Content style={{ flex: 1, overflow: 'auto', padding: 16 }}>
          <CenterPanel metadata={metadata} />
        </Content>
        <div
          onClick={() => setRightCollapsed(!rightCollapsed)}
          style={{
            width: 16, display: 'flex', alignItems: 'center', justifyContent: 'center',
            cursor: 'pointer', background: '#fafafa',
            borderLeft: '1px solid #f0f0f0',
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
            paddingLeft: rightCollapsed ? 0 : 12,
          }}
        >
          <RightPanel metadata={metadata} ruleDetail={ruleDetail} />
        </div>
      </div>

      <DryRunDrawer
        open={dryRunOpen}
        onClose={() => setDryRunOpen(false)}
        ruleVersionId={dryRunVersion?.ruleVersionId}
        versionLabel={dryRunVersion?.version}
        ruleId={ruleDetail.ruleDefinitionId}
        sceneCode={ruleDetail.sceneCode}
        eventTypes={metadata?.eventTypes ?? ruleDetail.triggerEventTypes ?? []}
        payloadFieldNames={metadata?.payloadFieldNames ?? []}
        payloadFieldTypes={metadata?.payloadFieldTypes}
      />

      <RuleAnalysisDrawer
        open={analysisOpen}
        onClose={() => setAnalysisOpen(false)}
        sceneCode={ruleDetail.sceneCode}
        report={analysisReport}
        loading={analysisLoading}
        onReanalyze={() => fetchAnalysis(ruleDetail.sceneCode, Number(ruleDetail.tenantId) || currentId || 0)}
        onLocate={handleLocate}
      />
    </Layout>
  );
}
