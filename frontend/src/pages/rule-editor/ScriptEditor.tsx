import { Input } from 'antd';
import { useRuleStore } from '@/store/ruleStore';

export default function ScriptEditor() {
  const { script, setScript } = useRuleStore();

  const lang = script?.lang ?? 'CEL';
  const source = script?.source ?? '';

  return (
    <div style={{ padding: 8 }}>
      <Input.TextArea
        rows={18}
        value={source}
        onChange={(e) => setScript({ lang, source: e.target.value })}
        placeholder="输入脚本，例如: metrics.amount > 1000"
      />
    </div>
  );
}
