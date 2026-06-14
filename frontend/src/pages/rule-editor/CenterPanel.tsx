import { useTranslation } from 'react-i18next';
import { useRuleStore } from '@/store/ruleStore';
import type { SceneMetadata as SceneMetadataType } from '@/types';
import ConditionTreeEditor from './ConditionTreeEditor';

interface Props { metadata: SceneMetadataType | null; }

export default function CenterPanel({ metadata }: Props) {
  const { t } = useTranslation('rule');
  const { ast, setAst, kind } = useRuleStore();

  if (kind !== 'AST_BOOLEAN') {
    return (
      <div style={{ padding: 40, textAlign: 'center', color: '#999' }}>
        {t('editor.centerPanel.placeholder')} ({kind})
      </div>
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
