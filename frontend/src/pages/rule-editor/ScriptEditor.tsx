import { useEffect, useRef, useMemo } from 'react';
import { Tag } from 'antd';
import { EditorView, basicSetup } from 'codemirror';
import { oneDark } from '@codemirror/theme-one-dark';
import { EditorState, Compartment } from '@codemirror/state';
import { javascript } from '@codemirror/lang-javascript';
import { json } from '@codemirror/lang-json';
import { autocompletion, type CompletionContext } from '@codemirror/autocomplete';
import type { MetricDescriptor } from '@/types';

interface Props {
  /** 受控脚本载体（source + lang）；null 视为空脚本、默认 CEL。 */
  script: { source: string; lang: string } | null;
  onChange: (script: { source: string; lang: string }) => void;
  availableMetrics: MetricDescriptor[];
  payloadFieldNames: string[];
}

function langExtension(lang: string) {
  // 后端 ExpressionLang tag 为全大写 JSONLOGIC（非 JsonLogic），匹配错会让 JsonLogic 规则用 JS 高亮
  if (lang === 'JSONLOGIC') return json();
  return javascript();
}

const languageCompartment = new Compartment();
const autocompleteCompartment = new Compartment();

/** 根据上下文提供补全：metrics. → 指标列表，payload. → 字段列表 */
function scriptCompletions(ctx: CompletionContext, metrics: MetricDescriptor[], payloads: string[]) {
  const word = ctx.matchBefore(/(?:metrics|payload|subject)\.(\w*)/);
  if (!word) return null;

  const prefix = word.text.split('.')[0];
  const partial = word.text.split('.')[1] ?? '';

  if (prefix === 'metrics') {
    return { from: word.from + 'metrics.'.length, options: metrics
      .filter((m) => m.metricCode.startsWith(partial))
      .map((m) => ({ label: m.metricCode, type: 'property' })),
    };
  }
  if (prefix === 'payload') {
    return { from: word.from + 'payload.'.length, options: payloads
      .filter((f) => f.startsWith(partial))
      .map((f) => ({ label: f, type: 'property' })),
    };
  }
  if (prefix === 'subject') {
    const builtins = ['id', 'type']
      .filter((s) => s.startsWith(partial))
      .map((s) => ({ label: s, type: 'property' }));
    return { from: word.from + 'subject.'.length, options: builtins };
  }
  return null;
}

export default function ScriptEditor({ script, onChange, availableMetrics, payloadFieldNames }: Props) {
  const containerRef = useRef<HTMLDivElement>(null);
  const viewRef = useRef<EditorView | null>(null);
  const langRef = useRef(script?.lang ?? 'CEL');
  const updatingFromOutside = useRef(false);

  const lang = script?.lang ?? 'CEL';

  const completeFn = useMemo(
    () => (ctx: CompletionContext) => scriptCompletions(ctx, availableMetrics, payloadFieldNames),
    [availableMetrics, payloadFieldNames],
  );

  // 初始化只执行一次
  useEffect(() => {
    if (!containerRef.current || viewRef.current) return;

    const state = EditorState.create({
      doc: script?.source ?? '',
      extensions: [
        basicSetup,
        oneDark,
        languageCompartment.of(langExtension(lang)),
        autocompleteCompartment.of(autocompletion({ override: [completeFn] })),
        EditorView.theme({
          '&': { height: '400px' },
          '.cm-scroller': { overflow: 'auto' },
        }),
        EditorView.updateListener.of((update) => {
          if (update.docChanged && !updatingFromOutside.current) {
            onChange({ lang: langRef.current, source: update.state.doc.toString() });
          }
        }),
      ],
    });

    viewRef.current = new EditorView({ state, parent: containerRef.current });
    return () => { viewRef.current?.destroy(); viewRef.current = null; };
  }, []);

  // 语言切换时只换语法扩展
  useEffect(() => {
    langRef.current = lang;
    viewRef.current?.dispatch({
      effects: languageCompartment.reconfigure(langExtension(lang)),
    });
  }, [lang]);

  // 可用变量变化时更新补全
  useEffect(() => {
    viewRef.current?.dispatch({
      effects: autocompleteCompartment.reconfigure(autocompletion({ override: [completeFn] })),
    });
  }, [completeFn]);

  // 外部来源变更时同步
  useEffect(() => {
    const view = viewRef.current;
    if (!view) return;
    const extSource = script?.source ?? '';
    if (view.state.doc.toString() !== extSource) {
      updatingFromOutside.current = true;
      view.dispatch({
        changes: { from: 0, to: view.state.doc.length, insert: extSource },
      });
      updatingFromOutside.current = false;
    }
  }, [script?.source]);

  return (
    <div style={{ padding: 8 }}>
      <div style={{ marginBottom: 8 }}>
        <Tag color="blue">{lang}</Tag>
      </div>
      <div ref={containerRef} style={{ border: '1px solid #d9d9d9', borderRadius: 6, overflow: 'hidden', height: 400 }} />
    </div>
  );
}
