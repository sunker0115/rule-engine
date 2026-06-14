import { Tabs, Select } from 'antd';
import { useTranslation } from 'react-i18next';
import { useRuleStore } from '@/store/ruleStore';
import RolloutSlider from '@/components/rollout-slider';
import DecisionBindingEditor from './DecisionBindingEditor';
import type { RuleDetail as RuleDetailType, SceneMetadata as SceneMetadataType } from '@/types';

interface Props {
  metadata: SceneMetadataType | null;
  ruleDetail: RuleDetailType;
}

export default function RightPanel({ metadata: _metadata, ruleDetail }: Props) {
  const { t } = useTranslation('rule');
  const { preGates, decisionBindings, script, setPreGates, setDecisionBindings, setScript } = useRuleStore();

  const showBinding = ruleDetail.kind !== 'DECISION_TREE' && ruleDetail.kind !== 'DECISION_TABLE';

  const tabItems = [
    ...(ruleDetail.kind === 'EXPRESSION_SCRIPT' ? [{
      key: 'executor',
      label: 'Executor',
      children: (
        <div style={{ padding: 16 }}>
          <Select
            style={{ width: '100%' }}
            value={script?.lang ?? 'CEL'}
            onChange={(lang) => setScript({ lang, source: script?.source ?? '' })}
            options={[
              { value: 'CEL', label: 'CEL' },
              { value: 'Aviator', label: 'Aviator' },
              { value: 'QLExpress', label: 'QLExpress' },
              { value: 'JsonLogic', label: 'JsonLogic' },
              { value: 'JEXL', label: 'JEXL' },
              { value: 'Groovy', label: 'Groovy' },
            ]}
          />
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
