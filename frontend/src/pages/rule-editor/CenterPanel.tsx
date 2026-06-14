import { useTranslation } from 'react-i18next';
import { useRuleStore } from '@/store/ruleStore';
import type { SceneMetadata as SceneMetadataType, ScorecardRootNode } from '@/types';
import ConditionTreeEditor from './ConditionTreeEditor';
import ScorecardEditor from './ScorecardEditor';

interface Props { metadata: SceneMetadataType | null; }

export default function CenterPanel({ metadata }: Props) {
  const { t } = useTranslation('rule');
  const { ast, setAst, kind } = useRuleStore();

  const shared = {
    conditionTypes: metadata?.conditionTypes ?? [],
    availableMetrics: metadata?.availableMetrics ?? [],
    payloadFieldNames: metadata?.payloadFieldNames ?? [],
  };

  if (kind !== 'AST_BOOLEAN' && kind !== 'SCORECARD' && kind !== 'DECISION_TREE' && kind !== 'DECISION_TABLE' && kind !== 'EXPRESSION_SCRIPT') {
    return (
      <div style={{ padding: 40, textAlign: 'center', color: '#999' }}>
        {t('editor.centerPanel.placeholder')} ({kind})
      </div>
    );
  }

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

  if (kind === 'DECISION_TREE' || kind === 'DECISION_TABLE') {
    return (
      <div style={{ padding: 40, textAlign: 'center', color: '#999' }}>
        {t('editor.centerPanel.placeholder')} ({kind})
      </div>
    );
  }

  return (
    <ConditionTreeEditor
      ast={ast}
      {...shared}
      onChange={setAst}
    />
  );
}
