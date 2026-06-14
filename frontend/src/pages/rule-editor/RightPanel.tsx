import { Tabs } from 'antd';
import { useTranslation } from 'react-i18next';
import { useRuleStore } from '@/store/ruleStore';
import ParamsSchemaForm from '@/components/params-schema-form';
import RolloutSlider from '@/components/rollout-slider';
import DecisionBindingEditor from './DecisionBindingEditor';
import type { RuleDetail as RuleDetailType, SceneMetadata as SceneMetadataType } from '@/types';

interface Props {
  metadata: SceneMetadataType | null;
  ruleDetail: RuleDetailType;
}

export default function RightPanel({ metadata, ruleDetail }: Props) {
  const { t } = useTranslation('rule');
  const { preGates, decisionBindings, setPreGates, setDecisionBindings } = useRuleStore();

  const tabItems = [
    {
      key: 'property',
      label: t('editor.rightPanel.property'),
      children: (
        <div style={{ padding: 16, color: '#999' }}>
          {t('editor.rightPanel.noSelection')}
        </div>
      ),
    },
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
    {
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
    },
  ];

  return <Tabs items={tabItems} />;
}
