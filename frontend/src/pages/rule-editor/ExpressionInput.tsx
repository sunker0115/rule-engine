import { useEffect, useRef, useMemo } from 'react';
import { EditorView, basicSetup } from 'codemirror';
import { oneDark } from '@codemirror/theme-one-dark';
import { EditorState, Compartment } from '@codemirror/state';
import { javascript } from '@codemirror/lang-javascript';
import { autocompletion, type CompletionContext } from '@codemirror/autocomplete';
import type { MetricDescriptor } from '@/types';
import { expressionCompletions } from './expressionCompletions';

interface Props {
  value: string;
  onChange: (value: string) => void;
  lang?: string;
  availableMetrics: MetricDescriptor[];
  payloadFieldNames: string[];
  payloadFieldTypes?: Record<string, string>;
}

const autocompleteCompartment = new Compartment();

/** 小型表达式输入框（单行 CodeMirror），用于 Flow Switch/Transform 等场景。 */
export default function ExpressionInput({ value, onChange, availableMetrics, payloadFieldNames, payloadFieldTypes }: Props) {
  const containerRef = useRef<HTMLDivElement>(null);
  const viewRef = useRef<EditorView | null>(null);
  const updatingFromOutside = useRef(false);

  const types = payloadFieldTypes ?? {};

  const completeFn = useMemo(
    () => (ctx: CompletionContext) => expressionCompletions(ctx, availableMetrics, payloadFieldNames, types),
    [availableMetrics, payloadFieldNames, types],
  );

  useEffect(() => {
    if (!containerRef.current || viewRef.current) return;

    const state = EditorState.create({
      doc: value,
      extensions: [
        basicSetup,
        oneDark,
        javascript(),
        autocompleteCompartment.of(autocompletion({ override: [completeFn] })),
        EditorView.theme({
          '&': { height: 'auto', minHeight: '32px' },
          '.cm-scroller': { overflow: 'auto', maxHeight: '120px' },
          '.cm-content': { padding: '4px 8px' },
          '.cm-line': { lineHeight: '22px' },
        }),
        EditorView.updateListener.of((update) => {
          if (update.docChanged && !updatingFromOutside.current) {
            onChange(update.state.doc.toString());
          }
        }),
      ],
    });

    viewRef.current = new EditorView({ state, parent: containerRef.current });
    return () => { viewRef.current?.destroy(); viewRef.current = null; };
  }, []);

  useEffect(() => {
    viewRef.current?.dispatch({
      effects: autocompleteCompartment.reconfigure(autocompletion({ override: [completeFn] })),
    });
  }, [completeFn]);

  // 外部变更同步
  useEffect(() => {
    const view = viewRef.current;
    if (!view) return;
    if (view.state.doc.toString() !== value) {
      updatingFromOutside.current = true;
      view.dispatch({
        changes: { from: 0, to: view.state.doc.length, insert: value },
      });
      updatingFromOutside.current = false;
    }
  }, [value]);

  return (
    <div
      ref={containerRef}
      style={{ border: '1px solid #d9d9d9', borderRadius: 6, overflow: 'hidden' }}
    />
  );
}
