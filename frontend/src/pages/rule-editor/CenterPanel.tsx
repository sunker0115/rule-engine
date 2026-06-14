import { QueryBuilder } from 'react-querybuilder';
import 'react-querybuilder/dist/query-builder.css';
import { useTranslation } from 'react-i18next';
import { useRuleStore } from '@/store/ruleStore';
import { astToQueryBuilder, queryBuilderToAst } from '@/utils/ast-converter';
import type { SceneMetadata as SceneMetadataType } from '@/types';
import type { Field, RuleGroupType } from 'react-querybuilder';

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

  const fields: Field[] = (metadata?.conditionTypes ?? []).map((ct) => ({
    name: ct.code,
    label: ct.displayName,
  }));

  const query = astToQueryBuilder(ast);

  const handleQueryChange = (newQuery: RuleGroupType) => {
    setAst(queryBuilderToAst(newQuery));
  };

  return (
    <QueryBuilder
      fields={fields}
      query={query}
      onQueryChange={handleQueryChange}
    />
  );
}
