import { Tabs, Select, Typography } from 'antd';
import { useTranslation } from 'react-i18next';
import { useRuleStore } from '@/store/ruleStore';
import RolloutSlider from '@/components/rollout-slider';
import TimeWindowPicker from '@/components/time-window-picker';
import DecisionBindingEditor from './DecisionBindingEditor';
import type {
  PreGate,
  RuleDetail as RuleDetailType,
  SceneMetadata as SceneMetadataType,
} from '@/types';

interface Props {
  metadata: SceneMetadataType | null;
  ruleDetail: RuleDetailType;
}

export default function RightPanel({ metadata, ruleDetail }: Props) {
  const { t } = useTranslation('rule');
  const { preGates, decisionBindings, script, setPreGates, setDecisionBindings, setScript } = useRuleStore();

  // 评分卡/决策树/决策表决策由自身结构内联，DECISION_FLOW 决策由 Output 节点内联，均不单独配置绑定
  const showBinding =
    ruleDetail.kind !== 'DECISION_TREE' &&
    ruleDetail.kind !== 'DECISION_TABLE' &&
    ruleDetail.kind !== 'SCORECARD' &&
    ruleDetail.kind !== 'DECISION_FLOW';

  const langs = metadata?.expressionLangs ?? ['CEL'];
  const langOptions = langs.map((l) => ({ value: l, label: l }));

  const currentLang = script?.lang ?? langs[0] ?? 'CEL';

  // 一个 gateType 至多一条；params 全空时移除该 gate，保留其它 gate（灰度与时段可并存）
  const upsertGate = (gate: PreGate) => {
    const others = (preGates ?? []).filter((g) => g.gateType !== gate.gateType);
    const isEmpty = Object.values(gate.params).every((v) => v === undefined || v === null);
    setPreGates(isEmpty ? others : [...others, gate]);
  };

  const rolloutParams =
    preGates?.find((g): g is Extract<PreGate, { gateType: 'ROLLOUT' }> => g.gateType === 'ROLLOUT')?.params ?? {};
  const timeWindowParams =
    preGates?.find((g): g is Extract<PreGate, { gateType: 'TIME_WINDOW' }> => g.gateType === 'TIME_WINDOW')?.params ?? {};

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
          <Typography.Title level={5} style={{ marginTop: 0 }}>
            {t('preGate.rolloutTitle')}
          </Typography.Title>
          <RolloutSlider value={rolloutParams} onChange={(params) => upsertGate({ gateType: 'ROLLOUT', params })} />
          <Typography.Title level={5} style={{ marginTop: 24 }}>
            {t('preGate.timeWindowTitle')}
          </Typography.Title>
          <TimeWindowPicker value={timeWindowParams} onChange={(params) => upsertGate({ gateType: 'TIME_WINDOW', params })} />
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
