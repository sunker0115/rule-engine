import { Select, Input } from 'antd';
import { useRuleStore } from '@/store/ruleStore';

export default function ScriptEditor() {
  const { script, setScript } = useRuleStore();

  const lang = script?.lang ?? 'CEL';
  const source = script?.source ?? '';

  return (
    <div style={{ padding: 8 }}>
      <div style={{ marginBottom: 12, display: 'flex', alignItems: 'center', gap: 8 }}>
        <span style={{ fontSize: 13 }}>脚本语言：</span>
        <Select
          style={{ width: 140 }}
          value={lang}
          onChange={(v) => setScript({ lang: v, source })}
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
      <Input.TextArea
        rows={16}
        value={source}
        onChange={(e) => setScript({ lang, source: e.target.value })}
        placeholder="输入脚本，例如: metrics.amount > 1000"
      />
    </div>
  );
}
