import { QueryBuilder } from 'react-querybuilder';
import 'react-querybuilder/dist/query-builder.css';
import { useTranslation } from 'react-i18next';
import { useRuleStore } from '@/store/ruleStore';
import { astToQueryBuilder, queryBuilderToAst, getParams } from '@/utils/ast-converter';
import type { SceneMetadata as SceneMetadataType } from '@/types';
import type { Field, RuleGroupType, RuleType, ValueEditorProps } from 'react-querybuilder';
import { Select, Input, Space, Tag } from 'antd';

interface Props { metadata: SceneMetadataType | null; }

/** 自定义 value 编辑器：显示 metric code / payload field + 只读 params */
function ValueEditor(props: ValueEditorProps) {
  const { value, operator, handleOnChange, rule } = props;
  const ruleId = (rule as RuleType).id;
  const params = ruleId ? getParams(ruleId) : {};
  const paramKeys = Object.keys(params).filter(k => k !== 'dataType');

  return (
    <Space direction="vertical" size={4}>
      {operator === 'METRIC' && (
        <Select
          style={{ width: 120 }}
          showSearch
          mode="tags"
          maxCount={1}
          value={value ? [value] : []}
          onChange={(vals) => handleOnChange(vals[0] ?? '')}
          placeholder="metric"
          options={[]}
        />
      )}
      {operator === 'PAYLOAD' && (
        <Input
          style={{ width: 120 }}
          value={value}
          onChange={(e) => handleOnChange(e.target.value)}
          placeholder="payload 字段"
        />
      )}
      {paramKeys.length > 0 && (
        <Space size={4} wrap>
          {paramKeys.map((k) => (
            <Tag key={k} color="default" style={{ fontSize: 11 }}>
              {k}={JSON.stringify(params[k])}
            </Tag>
          ))}
        </Space>
      )}
    </Space>
  );
}

const VALUE_SOURCE_OPS = [
  { name: '指标值', label: '指标', value: 'METRIC' },
  { name: 'Payload', label: 'Payload', value: 'PAYLOAD' },
];

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
    operators: VALUE_SOURCE_OPS,
    defaultOperator: 'METRIC',
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
      controlElements={{
        valueEditor: ValueEditor,
      }}
    />
  );
}
