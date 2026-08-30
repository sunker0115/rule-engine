import { useEffect, useState } from 'react';
import { useParams } from 'react-router-dom';
import { Layout, Spin } from 'antd';
import { MenuFoldOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { useTenantStore } from '@/store/tenantStore';
import { useRuleStore } from '@/store/ruleStore';
import { useLineageStore } from '@/store/lineageStore';
import { getRule } from '@/api/rule';
import { getSceneMetadata } from '@/api/metadata';
import { getScene, getAnalysis } from '@/api/scene';
import { getMetricUsageCounts, getMetricSources } from '@/api/metric';
import { getDecisionUsageCounts, getDecisionSources } from '@/api/decision';
import type { RuleDetail as RuleDetailType, SceneMetadata as SceneMetadataType, RuleVersionItem, RuleSetAnalysisReport } from '@/types';
import { bodyToCarriers } from '@/types';
import LeftPanel from './LeftPanel';
import CenterPanel from './CenterPanel';
import RightPanel from './RightPanel';
import DryRunDrawer from './DryRunDrawer';
import RuleAnalysisDrawer from './RuleAnalysisDrawer';
import LineageDrawer from '@/components/lineage/LineageDrawer';
import { isAnalyzableKind } from './analysisSummary';
import { extractPayloadSchema } from '@/utils/payloadSchema';

const { Sider, Content } = Layout;

export default function RuleEditor() {
  const { ruleId } = useParams<{ ruleId: string }>();
  const { currentId } = useTenantStore();
  const { t } = useTranslation('rule');
  const tl = useTranslation('lineage').t;
  const { loadFromDetail } = useRuleStore();
  const { setUsage, openRequest, clearOpen } = useLineageStore();
  const [loading, setLoading] = useState(true);
  const [ruleDetail, setRuleDetail] = useState<RuleDetailType | null>(null);
  const [metadata, setMetadata] = useState<SceneMetadataType | null>(null);
  const [dryRunOpen, setDryRunOpen] = useState(false);
  // 当前要试算的目标版本；null 表示走默认（最新 DRAFT/ACTIVE 版本）
  const [dryRunTarget, setDryRunTarget] = useState<RuleVersionItem | null>(null);
  const [leftCollapsed, setLeftCollapsed] = useState(false);
  const [rightCollapsed, setRightCollapsed] = useState(true); // 默认关闭
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
    // 切换规则（同路由切 ruleId，组件不 remount）时清掉残留的血缘抽屉打开请求
    clearOpen();
    setLoading(true);
    try {
      const detailRes = await getRule(currentId, Number(ruleId));
      const detail = detailRes;
      if (detail) {
        setRuleDetail(detail);
        const carriers = bodyToCarriers(detail.body);
        loadFromDetail(
          carriers.conditionAst,
          detail.decisionBindings ?? [],
          detail.preGates ?? [],
          detail.triggerEventTypes ?? [],
          detail.kind,
          carriers.script,
          carriers.flowGraph,
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

        // 反向血缘计数：一次性拉取 metric/decision 被引用计数，下传给徽标（经 lineageStore，避免穿透多层 props）
        const [metricCounts, decisionCounts] = await Promise.all([
          getMetricUsageCounts(currentId),
          getDecisionUsageCounts(currentId),
        ]);
        setUsage(
          Object.fromEntries((metricCounts ?? []).map((c) => [c.code, c.count])),
          Object.fromEntries((decisionCounts ?? []).map((c) => [c.code, c.count])),
        );

        // 分析随重载刷新：发布/丢弃/新版本走 load 时一并重算（这些操作改了 ACTIVE/版本，本就该全量重载）；
        // 非可分析 kind 清空。保存草稿不走 load——它只让分析失效，走轻量 reanalyze（见 onReanalyze）。
        if (isAnalyzableKind(detail.kind)) {
          fetchAnalysis(detail.sceneCode, Number(detail.tenantId) || currentId || 0);
        } else {
          setAnalysisReport(null);
        }
      }
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { load(); }, [currentId, ruleId]);

  // 轻量重算：仅刷新规则集分析（保存草稿后用——内容变了但状态/版本/计数都没变，无需全量 load）
  const reanalyze = () => {
    if (ruleDetail && isAnalyzableKind(ruleDetail.kind)) {
      fetchAnalysis(ruleDetail.sceneCode, Number(ruleDetail.tenantId) || currentId || 0);
    }
  };

  // 定位 finding 对应规则：编辑器为单规则视图，关闭抽屉让用户看到当前规则编辑区与 badge
  const handleLocate = () => setAnalysisOpen(false);

  // 一个共享抽屉实例：按 openRequest.kind 决定标题与 fetcher（metric 走版本无关 /sources，与 decision 同口径）
  const lineageTitle = openRequest
    ? openRequest.kind === 'metric'
      ? tl('metricDrawerTitle', { code: openRequest.code })
      : tl('drawerTitle', { code: openRequest.code })
    : '';
  const lineageFetcher = openRequest?.kind === 'metric' ? getMetricSources : getDecisionSources;

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
      <Sider width={leftCollapsed ? 0 : 260} style={{ background: '#fafafa', borderRight: '1px solid #f0f0f0', overflow: leftCollapsed ? 'hidden' : 'auto', transition: 'width 0.2s', minWidth: 0 }}>
        <LeftPanel
          ruleDetail={ruleDetail}
          onOpenDryRun={openDryRun}
          onUpdated={load}
          onReanalyze={reanalyze}
          analysisReport={analysisReport}
          onOpenAnalysis={() => setAnalysisOpen(true)}
        />
      </Sider>
      {/* 左栏折叠手柄 */}
      <div onClick={() => setLeftCollapsed(!leftCollapsed)}
        style={{ width: 12, display: 'flex', alignItems: 'center', justifyContent: 'center', cursor: 'pointer', background: leftCollapsed ? '#fff' : '#fafafa', borderRight: leftCollapsed ? '1px solid #f0f0f0' : 'none', flex: 'none' }}>
        <MenuFoldOutlined style={{ fontSize: 10, color: '#999', transform: leftCollapsed ? 'rotate(180deg)' : undefined }} />
      </div>
      <div style={{ display: 'flex', flex: 1, minHeight: 0 }}>
        <Content style={{ flex: 1, overflow: 'hidden', display: 'flex', flexDirection: 'column', padding: 0 }}>
          <CenterPanel metadata={metadata} sceneCode={ruleDetail.sceneCode} ruleCode={ruleDetail.code} tenantId={Number(ruleDetail.tenantId) || currentId || 0} analysisReport={analysisReport} onLeafChanged={reanalyze} onOpenRightPanel={() => setRightCollapsed(false)} onCloseRightPanel={() => { setRightCollapsed(true); }} />
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
        paramKeys={ruleDetail.body?.type === 'ScriptBody' ? Object.keys(ruleDetail.body.script?.params ?? {}) : []}
      />

      <RuleAnalysisDrawer
        open={analysisOpen}
        onClose={() => setAnalysisOpen(false)}
        sceneCode={ruleDetail.sceneCode}
        report={analysisReport}
        loading={analysisLoading}
        onReanalyze={reanalyze}
        onLocate={handleLocate}
      />

      <LineageDrawer
        open={!!openRequest}
        code={openRequest?.code ?? ''}
        title={lineageTitle}
        tenantId={currentId ?? 0}
        fetcher={lineageFetcher}
        onClose={clearOpen}
      />
    </Layout>
  );
}
