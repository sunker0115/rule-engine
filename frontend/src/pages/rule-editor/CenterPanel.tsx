import { useEffect, useState } from 'react';
import { Typography } from 'antd';
import { useTranslation } from 'react-i18next';
import { useRuleStore } from '@/store/ruleStore';
import { useTenantStore } from '@/store/tenantStore';
import { listDecisions } from '@/api/decision';
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
}

export default function CenterPanel({ metadata, sceneCode, ruleCode, tenantId, analysisReport, onLeafChanged }: Props) {
  const { t } = useTranslation('rule');
  const { ast, setAst, kind, script, setScript, flowGraph, setFlowGraph } = useRuleStore();
  const { currentId } = useTenantStore();
  const [decisions, setDecisions] = useState<DecisionItem[]>([]);

  useEffect(() => {
    if (currentId) listDecisions(currentId).then((d) => setDecisions(d ?? []));
  }, [currentId]);

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
        decisions={decisions}
      />
    );
  };

  return (
    <div>
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 16, padding: '0 8px' }}>
        <Typography.Text strong>{t(`enum.kind.${kind}`)}</Typography.Text>
      </div>
      {renderEditor()}
    </div>
  );
}
