import { useEffect, useRef } from 'react';
import { Tag } from 'antd';
import { useRuleStore } from '@/store/ruleStore';
import { EditorView, basicSetup } from 'codemirror';
import { EditorState, Compartment } from '@codemirror/state';
import { javascript } from '@codemirror/lang-javascript';
import { json } from '@codemirror/lang-json';

function langExtension(lang: string) {
  if (lang === 'JsonLogic') return json();
  return javascript();
}

const languageCompartment = new Compartment();

export default function ScriptEditor() {
  const { script, setScript } = useRuleStore();
  const containerRef = useRef<HTMLDivElement>(null);
  const viewRef = useRef<EditorView | null>(null);
  const langRef = useRef(script?.lang ?? 'CEL');
  const updatingFromOutside = useRef(false);

  const lang = script?.lang ?? 'CEL';

  // 初始化只执行一次
  useEffect(() => {
    if (!containerRef.current || viewRef.current) return;

    const state = EditorState.create({
      doc: script?.source ?? '',
      extensions: [
        basicSetup,
        languageCompartment.of(langExtension(lang)),
        EditorView.updateListener.of((update) => {
          if (update.docChanged && !updatingFromOutside.current) {
            setScript({ lang: langRef.current, source: update.state.doc.toString() });
          }
        }),
      ],
    });

    viewRef.current = new EditorView({ state, parent: containerRef.current });
    return () => { viewRef.current?.destroy(); viewRef.current = null; };
  }, []);

  // 语言切换时只换语法扩展，不重建编辑器
  useEffect(() => {
    langRef.current = lang;
    viewRef.current?.dispatch({
      effects: languageCompartment.reconfigure(langExtension(lang)),
    });
  }, [lang]);

  // 外部来源变更（如切换规则）时同步到编辑器
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
      <div ref={containerRef} style={{ border: '1px solid #d9d9d9', borderRadius: 6, overflow: 'hidden' }} />
    </div>
  );
}
