import { useTranslation } from 'react-i18next';
import type {
  AstNode, ScorecardRootNode, IfNode, DecisionTableNode,
  ConditionTypeMeta, MetricDescriptor, DecisionItem, RuleKind,
} from '@/types';
import ConditionTreeEditor from './ConditionTreeEditor';
import ScorecardEditor from './ScorecardEditor';
import DecisionTreeEditor from './DecisionTreeEditor';
import DecisionTableEditor from './DecisionTableEditor';
import ScriptEditor from './ScriptEditor';

interface Props {
  /** 规则体的种类（DECISION_FLOW 以外的 5 种，由画布单独承载）。 */
  kind: RuleKind;
  ast: AstNode | null;
  script: { source: string; lang: string } | null;
  onAstChange: (ast: AstNode) => void;
  onScriptChange: (script: { source: string; lang: string }) => void;
  conditionTypes: ConditionTypeMeta[];
  availableMetrics: MetricDescriptor[];
  payloadFieldNames: string[];
  /** 字段名 → dataType，透传给表达式编辑器做补全类型提示 */
  payloadFieldTypes?: Record<string, string>;
  decisions: DecisionItem[];
  tenantId?: number;
  sceneCode?: string;
}

/**
 * 按 kind 分派到对应的受控规则体编辑器（AST 布尔树 / 评分卡 / 决策树 / 决策表 / 脚本）。
 * 与承载分离：不读 store、不含标题头，供 CenterPanel（当前规则）与 flow 下钻抽屉（被引规则）共用。
 */
export default function RuleBodyEditor({
  kind, ast, script, onAstChange, onScriptChange,
  conditionTypes, availableMetrics, payloadFieldNames, payloadFieldTypes, decisions,
  tenantId, sceneCode,
}: Props) {
  const { t } = useTranslation('rule');
  const shared = { conditionTypes, availableMetrics, payloadFieldNames };

  if (kind === 'AST_BOOLEAN') {
    return <ConditionTreeEditor ast={ast} {...shared} onChange={onAstChange} />;
  }

  if (kind === 'SCORECARD') {
    const scorecardNode: ScorecardRootNode = (ast?.type === 'ScorecardRootNode')
      ? ast
      : { type: 'ScorecardRootNode', conditions: [], threshold: 0 };
    return <ScorecardEditor node={scorecardNode} {...shared} decisions={decisions} onChange={onAstChange} />;
  }

  if (kind === 'DECISION_TREE') {
    const ifNode: IfNode = (ast?.type === 'IfNode')
      ? ast
      : { type: 'IfNode', condition: { type: 'AndNode', children: [] }, thenBranch: { type: 'DecisionLeafNode', decisionCode: '', category: null }, elseBranch: null };
    return <DecisionTreeEditor ast={ifNode} {...shared} onChange={(node) => onAstChange(node as AstNode)} />;
  }

  if (kind === 'DECISION_TABLE') {
    const tableNode: DecisionTableNode = (ast?.type === 'DecisionTableNode')
      ? ast
      : { type: 'DecisionTableNode', columns: [{ metricCode: '', operator: 'EQ', dataType: null }], rows: [{ conditions: [null], decisionCode: '' }] };
    return <DecisionTableEditor node={tableNode} {...shared} onChange={(node) => onAstChange(node as AstNode)} />;
  }

  if (kind === 'EXPRESSION_SCRIPT') {
    return (
      <ScriptEditor
        script={script}
        onChange={onScriptChange}
        availableMetrics={availableMetrics}
        payloadFieldNames={payloadFieldNames}
        payloadFieldTypes={payloadFieldTypes}
        tenantId={tenantId}
        sceneCode={sceneCode}
      />
    );
  }

  return (
    <div style={{ padding: 40, textAlign: 'center', color: '#999' }}>
      {t('editor.centerPanel.placeholder')} ({kind})
    </div>
  );
}
