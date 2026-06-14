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
      label: t('editor.rightPanel.executor'),
      children: (
        <div style={{ padding: 16 }}>
          <Select
            style={{ width: '100%', marginBottom: 12 }}
            value={currentLang}
            onChange={(lang) => setScript({ lang, source: script?.source ?? '' })}
            options={langOptions}
          />
          <Typography.Paragraph
            type="secondary"
            style={{ fontSize: 12, whiteSpace: 'pre-line', background: '#f5f5f5', padding: 8, borderRadius: 4, margin: 0 }}
          >
            {t(`editor.script.syntaxHints.${currentLang}`)}
          </Typography.Paragraph>
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
