import { useEffect, useState } from 'react';
import { Typography } from 'antd';
import { useTranslation } from 'react-i18next';
import { useRuleStore } from '@/store/ruleStore';
import { useTenantStore } from '@/store/tenantStore';
import { listDecisions } from '@/api/decision';
import type { SceneMetadata as SceneMetadataType, ScorecardRootNode, IfNode, DecisionTableNode, AstNode, DecisionItem } from '@/types';
import ConditionTreeEditor from './ConditionTreeEditor';
import ScorecardEditor from './ScorecardEditor';
import DecisionTreeEditor from './DecisionTreeEditor';
import DecisionTableEditor from './DecisionTableEditor';
import ScriptEditor from './ScriptEditor';

interface Props { metadata: SceneMetadataType | null; }

export default function CenterPanel({ metadata }: Props) {
  const { t } = useTranslation('rule');
  const { ast, setAst, kind } = useRuleStore();
  const { currentId } = useTenantStore();
  const [decisions, setDecisions] = useState<DecisionItem[]>([]);

  useEffect(() => {
    if (currentId) listDecisions(currentId).then((d) => setDecisions(d ?? []));
  }, [currentId]);

  const shared = {
    conditionTypes: metadata?.conditionTypes ?? [],
    availableMetrics: metadata?.availableMetrics ?? [],
    payloadFieldNames: metadata?.payloadFieldNames ?? [],
  };

  // 各 kind 对应的编辑器；标题头在下方统一渲染
  const renderEditor = () => {
    // AST_BOOLEAN: 条件组合树
    if (kind === 'AST_BOOLEAN') {
      return (
        <ConditionTreeEditor
          ast={ast}
          {...shared}
          onChange={setAst}
        />
      );
    }

    // SCORECARD: 评分卡
    if (kind === 'SCORECARD') {
      const scorecardNode: ScorecardRootNode = (ast?.type === 'ScorecardRootNode')
        ? ast
        : { type: 'ScorecardRootNode', conditions: [], threshold: 0 };
      return (
        <ScorecardEditor
          node={scorecardNode}
          {...shared}
          decisions={decisions}
          onChange={setAst}
        />
      );
    }

    // DECISION_TREE: 决策树
    if (kind === 'DECISION_TREE') {
      const ifNode: IfNode = (ast?.type === 'IfNode')
        ? ast
        : { type: 'IfNode', condition: { type: 'AndNode', children: [] }, thenBranch: { type: 'DecisionLeafNode', decisionCode: '', category: null }, elseBranch: null };
      return (
        <DecisionTreeEditor
          ast={ifNode}
          {...shared}
          onChange={(node) => setAst(node as AstNode)}
        />
      );
    }

    // DECISION_TABLE: 决策表
    if (kind === 'DECISION_TABLE') {
      const tableNode: DecisionTableNode = (ast?.type === 'DecisionTableNode')
        ? ast
        : { type: 'DecisionTableNode', columns: [{ metricCode: '', operator: 'EQ', dataType: null }], rows: [{ conditions: [null], decisionCode: '' }] };
      return (
        <DecisionTableEditor
          node={tableNode}
          conditionTypes={shared.conditionTypes}
          availableMetrics={shared.availableMetrics}
          payloadFieldNames={shared.payloadFieldNames}
          onChange={(node) => setAst(node as AstNode)}
        />
      );
    }

    // EXPRESSION_SCRIPT: 脚本编辑器
    if (kind === 'EXPRESSION_SCRIPT') {
      return <ScriptEditor availableMetrics={shared.availableMetrics} payloadFieldNames={shared.payloadFieldNames} />;
    }

    // 未知: 占位
    return (
      <div style={{ padding: 40, textAlign: 'center', color: '#999' }}>
        {t('editor.centerPanel.placeholder')} ({kind})
      </div>
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
