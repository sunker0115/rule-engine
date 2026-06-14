import { useTranslation } from 'react-i18next';
import { useRuleStore } from '@/store/ruleStore';
import type { SceneMetadata as SceneMetadataType, ScorecardRootNode } from '@/types';
import ConditionTreeEditor from './ConditionTreeEditor';
import ScorecardEditor from './ScorecardEditor';

interface Props { metadata: SceneMetadataType | null; }

export default function CenterPanel({ metadata }: Props) {
  const { t } = useTranslation('rule');
  const { ast, setAst, kind } = useRuleStore();

  if (kind !== 'AST_BOOLEAN' && kind !== 'SCORECARD') {
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
        conditionTypes={metadata?.conditionTypes ?? []}
        availableMetrics={metadata?.availableMetrics ?? []}
        onChange={setAst}
      />
    );
  }

  return (
    <ConditionTreeEditor
      ast={ast}
      conditionTypes={metadata?.conditionTypes ?? []}
      availableMetrics={metadata?.availableMetrics ?? []}
      onChange={setAst}
    />
  );
}
