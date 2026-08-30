import { useEffect, useState } from 'react';
import { Typography } from 'antd';
import { useTranslation } from 'react-i18next';
import { useRuleStore } from '@/store/ruleStore';
import { useTenantStore } from '@/store/tenantStore';
import { listDecisions } from '@/api/decision';
import { listRules } from '@/api/rule';
import type { SceneMetadata as SceneMetadataType, DecisionItem, RuleSetAnalysisReport } from '@/types';
import RuleBodyEditor from './RuleBodyEditor';
import FlowCanvasEditor from './FlowCanvasEditor';

interface Props {
  metadata: SceneMetadataType | null;
  /** 当前规则场景码（DECISION_FLOW 画布用于筛选可引规则）。 */
  sceneCode: string;
  /** 当前规则逻辑编码（DECISION_FLOW 画布按此过滤图内 finding）。 */
  ruleCode: string;
  /** 当前租户 id（下钻编辑被引规则草稿用）。 */
  tenantId: number;
  /** 规则集分析报告（DECISION_FLOW 画布消费图内环/死节点 finding）。 */
  analysisReport?: RuleSetAnalysisReport | null;
  /** 下钻编辑/新建叶子规则后回调（触发规则集分析重算）。 */
  onLeafChanged?: () => void;
  /** 双击 flow 节点/边时打开右栏 */
  onOpenRightPanel?: () => void;
  /** 点画布空白处关闭右栏 */
  onCloseRightPanel?: () => void;
}

export default function CenterPanel({ metadata, sceneCode, ruleCode, tenantId, analysisReport, onLeafChanged, onOpenRightPanel, onCloseRightPanel }: Props) {
  const { t } = useTranslation('rule');
  const { ast, setAst, kind, script, setScript, flowGraph, setFlowGraph, setSelectedFlowNodeId, setSelectedFlowEdgeIndex, setFlowSceneRules } = useRuleStore();
  const { currentId } = useTenantStore();
  const [decisions, setDecisions] = useState<DecisionItem[]>([]);

  useEffect(() => {
    if (currentId) listDecisions(currentId).then((d) => setDecisions(d ?? []));
  }, [currentId]);

  // DECISION_FLOW：拉取 tenant 全量已发布规则写入 store（RuleRef 允许跨 Scene 引用），供 FlowCanvasEditor 和 RightPanel 共用
  // 排除自身与 DECISION_FLOW（防递归）；仅 PUBLISHED 可被引（RuleRef 冻结 ACTIVE 版本）
  useEffect(() => {
    if (!tenantId || kind !== 'DECISION_FLOW') return;
    listRules(tenantId, undefined, { page: 1, size: 500 }).then((data) => {
      setFlowSceneRules((data.items ?? [])
        .filter((r: any) => r.code !== ruleCode && r.status === 'PUBLISHED' && r.kind !== 'DECISION_FLOW')
        .map((r: any) => ({ code: r.code, name: r.name, ruleDefinitionId: r.ruleDefinitionId, kind: r.kind, sceneCode: r.sceneCode })));
    });
  }, [tenantId, kind, ruleCode, setFlowSceneRules]);

  const renderEditor = () => {
    // DECISION_FLOW: 决策图画布（与其它 5 种承载平级，独立编排层）
    if (kind === 'DECISION_FLOW') {
      return (
        <FlowCanvasEditor
          value={flowGraph}
          onChange={setFlowGraph}
          sceneCode={sceneCode}
          ruleCode={ruleCode}
          tenantId={tenantId}
          metadata={metadata}
          decisions={decisions}
          analysisReport={analysisReport}
          onLeafChanged={onLeafChanged}
          onSelectedNodeChange={setSelectedFlowNodeId}
          onSelectedEdgeChange={setSelectedFlowEdgeIndex}
          onOpenRightPanel={onOpenRightPanel}
          onCloseRightPanel={onCloseRightPanel}
        />
      );
    }
    // 其余 5 种承载走受控规则体编辑器
    return (
      <RuleBodyEditor
        kind={kind}
        ast={ast}
        script={script}
        onAstChange={setAst}
        onScriptChange={setScript}
        conditionTypes={metadata?.conditionTypes ?? []}
        availableMetrics={metadata?.availableMetrics ?? []}
        payloadFieldNames={metadata?.payloadFieldNames ?? []}
        payloadFieldTypes={metadata?.payloadFieldTypes}
        decisions={decisions}
        tenantId={tenantId}
        sceneCode={sceneCode}
      />
    );
  };

  // DECISION_FLOW 画布需撑满，不加 padding 和标题
  if (kind === 'DECISION_FLOW') {
    return <>{renderEditor()}</>;
  }

  return (
    <div style={{ padding: 16 }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 16 }}>
        <Typography.Text strong>{t(`enum.kind.${kind}`)}</Typography.Text>
      </div>
      {renderEditor()}
    </div>
  );
}
