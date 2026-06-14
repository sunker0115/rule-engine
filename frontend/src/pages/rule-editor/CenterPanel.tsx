import { useTranslation } from 'react-i18next';
import { useRuleStore } from '@/store/ruleStore';
import type { SceneMetadata as SceneMetadataType, ScorecardRootNode, IfNode, AstNode } from '@/types';
import ConditionTreeEditor from './ConditionTreeEditor';
import ScorecardEditor from './ScorecardEditor';
import DecisionTreeEditor from './DecisionTreeEditor';

interface Props { metadata: SceneMetadataType | null; }

export default function CenterPanel({ metadata }: Props) {
  const { t } = useTranslation('rule');
  const { ast, setAst, kind } = useRuleStore();

  const shared = {
    conditionTypes: metadata?.conditionTypes ?? [],
    availableMetrics: metadata?.availableMetrics ?? [],
    payloadFieldNames: metadata?.payloadFieldNames ?? [],
  };

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

  // DECISION_TABLE / EXPRESSION_SCRIPT / 未知: 占位
  return (
    <div style={{ padding: 40, textAlign: 'center', color: '#999' }}>
      {t('editor.centerPanel.placeholder')} ({kind})
    </div>
  );
}
