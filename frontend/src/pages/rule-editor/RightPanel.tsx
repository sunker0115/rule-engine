import { Tabs } from 'antd';
import { useTranslation } from 'react-i18next';
import type { RuleDetail as RuleDetailType, SceneMetadata as SceneMetadataType } from '@/types';

interface Props {
  metadata: SceneMetadataType | null;
  ruleDetail: RuleDetailType;
}

export default function RightPanel({ metadata: _metadata, ruleDetail }: Props) {
  const { t } = useTranslation('rule');

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
          <p>ROLLOUT: {ruleDetail.preGates?.[0]?.params?.percentage ?? 'N/A'}%</p>
        </div>
      ),
    },
    {
      key: 'binding',
      label: t('editor.rightPanel.decisionBinding'),
      children: (
        <div style={{ padding: 16 }}>
          {(ruleDetail.decisionBindings ?? []).map((b, i) => (
            <p key={i}>{b.decisionCode}</p>
          ))}
        </div>
      ),
    },
  ];

  return <Tabs items={tabItems} />;
}
