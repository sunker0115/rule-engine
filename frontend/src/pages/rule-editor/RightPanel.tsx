import { Tabs, Select, Typography } from 'antd';
import { useTranslation } from 'react-i18next';
import { useRuleStore } from '@/store/ruleStore';
import RolloutSlider from '@/components/rollout-slider';
import DecisionBindingEditor from './DecisionBindingEditor';
import type { RuleDetail as RuleDetailType, SceneMetadata as SceneMetadataType } from '@/types';

interface Props {
  metadata: SceneMetadataType | null;
  ruleDetail: RuleDetailType;
}

/** 语法提示 */
const SYNTAX_HINTS: Record<string, string> = {
  CEL: 'CEL 表达式，直接写条件：\nmetrics.amount > 1000\npayload.country == "CN"\n链接：&&  ||  分组：()',
  Aviator: '类 JS 语法：\nmetrics.amount > 1000 && payload.country == "CN"\n支持：if/else、三元、正则',
  QLExpress: '类 Java 语法：\nmetrics.amount > 1000 && payload.country == "CN"\n支持：for/while、自定义函数',
  JsonLogic: 'JSON 规则格式：\n{ "and": [\n  { ">": [{ "var": "metrics.amount" }, 1000] },\n  { "==": [{ "var": "payload.country" }, "CN"] }\n] }',
  JEXL: '类 Java 表达式：\nmetrics.amount > 1000 && payload.country == "CN"\n支持：方法调用、集合操作',
  Groovy: 'Groovy 脚本：\nif (metrics.amount > 1000) { return true }\nreturn false',
};

export default function RightPanel({ metadata, ruleDetail }: Props) {
  const { t } = useTranslation('rule');
  const { preGates, decisionBindings, script, setPreGates, setDecisionBindings, setScript } = useRuleStore();

  const showBinding = ruleDetail.kind !== 'DECISION_TREE' && ruleDetail.kind !== 'DECISION_TABLE';

  const langs = metadata?.expressionLangs ?? ['CEL'];
  const langOptions = langs.map((l) => ({ value: l, label: l }));

  const currentLang = script?.lang ?? langs[0] ?? 'CEL';

  const tabItems = [
    ...(ruleDetail.kind === 'EXPRESSION_SCRIPT' ? [{
      key: 'executor',
      label: 'Executor',
      children: (
        <div style={{ padding: 16 }}>
          <Select
            style={{ width: '100%', marginBottom: 12 }}
            value={currentLang}
            onChange={(lang) => setScript({ lang, source: script?.source ?? '' })}
            options={langOptions}
          />
          {SYNTAX_HINTS[currentLang] && (
            <Typography.Paragraph
              type="secondary"
              style={{ fontSize: 12, whiteSpace: 'pre-line', background: '#f5f5f5', padding: 8, borderRadius: 4, margin: 0 }}
            >
              {SYNTAX_HINTS[currentLang]}
            </Typography.Paragraph>
          )}
        </div>
      ),
    }] : []),
    {
      key: 'pregate',
      label: t('editor.rightPanel.preGate'),
      children: (
        <div style={{ padding: 16 }}>
          <RolloutSlider
            value={preGates?.[0]?.params ?? {}}
            onChange={(params) => setPreGates([{ gateType: 'ROLLOUT', params }])}
          />
        </div>
      ),
    },
    ...(showBinding ? [{
      key: 'binding',
      label: t('editor.rightPanel.decisionBinding'),
      children: (
        <div style={{ padding: 16 }}>
          <DecisionBindingEditor
            kind={ruleDetail.kind}
            value={decisionBindings}
            onChange={setDecisionBindings}
          />
        </div>
      ),
    }] : []),
  ];

  return <Tabs items={tabItems} />;
}
