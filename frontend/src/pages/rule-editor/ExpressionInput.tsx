import { useEffect, useRef, useMemo, useCallback } from 'react';
import { EditorView, basicSetup } from 'codemirror';
import { oneDark } from '@codemirror/theme-one-dark';
import { EditorState, Compartment } from '@codemirror/state';
import { javascript } from '@codemirror/lang-javascript';
import { autocompletion, type CompletionContext } from '@codemirror/autocomplete';
import { lintGutter, setDiagnostics, type Diagnostic } from '@codemirror/lint';
import type { MetricDescriptor } from '@/types';
import { expressionCompletions } from './expressionCompletions';
import { validateExpression } from '@/api/expression';

interface Props {
  value: string;
  onChange: (value: string) => void;
  lang?: string;
  availableMetrics: MetricDescriptor[];
  payloadFieldNames: string[];
  payloadFieldTypes?: Record<string, string>;
  /** 非空时启用实时类型诊断 */
  tenantId?: number;
  sceneCode?: string;
}

/**
 * 解析 CEL ExpressionCompileException 消息，提取行/列/消息。
 * 解析失败降级为全文单行提示。
 */
function parseCelErrors(errorText: string): Diagnostic[] {
  const re = /:(\d+):(\d+):(.+)/g;
  const diagnostics: Diagnostic[] = [];
  let m: RegExpExecArray | null;
  while ((m = re.exec(errorText)) !== null) {
    diagnostics.push({
      from: 0, to: 0,
      severity: 'error',
      message: m[3].trim(),
    });
  }
  if (diagnostics.length === 0) {
    diagnostics.push({ from: 0, to: 0, severity: 'error', message: errorText });
  }
  return diagnostics;
}

const autocompleteCompartment = new Compartment();

export default function ExpressionInput({ value, onChange, availableMetrics, payloadFieldNames, payloadFieldTypes, tenantId, sceneCode }: Props) {
  const containerRef = useRef<HTMLDivElement>(null);
  const viewRef = useRef<EditorView | null>(null);
  const updatingFromOutside = useRef(false);
  const lang = 'CEL';
  const types = payloadFieldTypes ?? {};

  const completeFn = useMemo(
    () => (ctx: CompletionContext) => expressionCompletions(ctx, availableMetrics, payloadFieldNames, types, []),
    [availableMetrics, payloadFieldNames, types],
  );

  const validateTimer = useRef<ReturnType<typeof setTimeout> | null>(null);

  const runValidate = useCallback((view: EditorView, source: string) => {
    if (validateTimer.current) clearTimeout(validateTimer.current);
    if (!tenantId || !sceneCode) return;
    validateTimer.current = setTimeout(async () => {
      try {
        const result = await validateExpression(tenantId, sceneCode, lang, source);
        if (result.valid) {
          view.dispatch(setDiagnostics(view.state, []));
        } else if (result.error) {
          view.dispatch(setDiagnostics(view.state, parseCelErrors(result.error)));
        }
      } catch {
        // 静默
      }
    }, 300);
  }, [tenantId, sceneCode]);

  useEffect(() => {
    if (!containerRef.current || viewRef.current) return;

    const state = EditorState.create({
      doc: value,
      extensions: [
        basicSetup,
        oneDark,
        lintGutter(),
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
            runValidate(update.view, update.state.doc.toString());
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
