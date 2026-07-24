import { useEffect, useRef, useMemo, useCallback } from 'react';
import { Tag } from 'antd';
import { EditorView, basicSetup } from 'codemirror';
import { oneDark } from '@codemirror/theme-one-dark';
import { EditorState, Compartment } from '@codemirror/state';
import { javascript } from '@codemirror/lang-javascript';
import { json } from '@codemirror/lang-json';
import { autocompletion, type CompletionContext } from '@codemirror/autocomplete';
import { lintGutter, setDiagnostics, type Diagnostic } from '@codemirror/lint';
import type { MetricDescriptor, ScriptParams } from '@/types';
import { expressionCompletions } from './expressionCompletions';
import { validateExpression } from '@/api/expression';

interface Props {
  script: { source: string; lang: string; params?: ScriptParams } | null;
  onChange: (script: { source: string; lang: string; params?: ScriptParams }) => void;
  availableMetrics: MetricDescriptor[];
  payloadFieldNames: string[];
  payloadFieldTypes?: Record<string, string>;
  /** 非空时启用实时类型诊断（debounced 300ms 调 /admin/v1/expressions/validate） */
  tenantId?: number;
  sceneCode?: string;
}

function langExtension(lang: string) {
  if (lang === 'JSONLOGIC') return json();
  return javascript();
}

/**
 * 解析 CEL ExpressionCompileException 消息，提取行/列/消息。
 * CEL 格式: “CEL 类型检查失败: ERROR: <input>:<line>:<col>: <message>”
 * 解析失败降级为全文单行提示。
 */
function parseCelErrors(errorText: string): Diagnostic[] {
  // CEL 标准格式: file:line:col: message
  const re = /:(\d+):(\d+):(.+)/g;
  const diagnostics: Diagnostic[] = [];
  let m: RegExpExecArray | null;
  while ((m = re.exec(errorText)) !== null) {
    // line/col 提取后暂不挂到精确位置（CodeMirror Diagnostic.from 需字节 offset），
    // 后续可用 state.doc.line() 映射实现精确标记
    diagnostics.push({
      from: 0, to: 0, // 降级：不精确到字符位，只标行
      severity: 'error',
      message: m[3].trim(),
      renderMessage: () => {
        const el = document.createElement('span');
        el.textContent = m![3].trim();
        return el;
      },
    });
  }
  if (diagnostics.length === 0) {
    diagnostics.push({ from: 0, to: 0, severity: 'error', message: errorText });
  }
  return diagnostics;
}

const languageCompartment = new Compartment();
const autocompleteCompartment = new Compartment();

export default function ScriptEditor({ script, onChange, availableMetrics, payloadFieldNames, payloadFieldTypes, tenantId, sceneCode }: Props) {
  const containerRef = useRef<HTMLDivElement>(null);
  const viewRef = useRef<EditorView | null>(null);
  const langRef = useRef(script?.lang ?? 'CEL');
  // updateListener 闭包在空依赖 init effect 内，直接读 script?.params 会读到旧值；
  // 用 ref 随 params 同步，onChange 时补回当前 params，避免每次编辑丢参数
  const paramsRef = useRef<ScriptParams | undefined>(script?.params);
  const updatingFromOutside = useRef(false);

  const lang = script?.lang ?? 'CEL';
  const types = payloadFieldTypes ?? {};

  const completeFn = useMemo(
    () => (ctx: CompletionContext) => expressionCompletions(ctx, availableMetrics, payloadFieldNames, types),
    [availableMetrics, payloadFieldNames, types],
  );

  const validateTimer = useRef<ReturnType<typeof setTimeout> | null>(null);

  /** 300ms debounced 调后端 typeCheck，结果转为 CodeMirror diagnostics。 */
  const runValidate = useCallback((view: EditorView, source: string, l: string) => {
    if (validateTimer.current) clearTimeout(validateTimer.current);
    if (!tenantId || !sceneCode) return;
    validateTimer.current = setTimeout(async () => {
      try {
        const result = await validateExpression(tenantId, sceneCode, l, source);
        if (result.valid) {
          view.dispatch(setDiagnostics(view.state, []));
        } else if (result.error) {
          view.dispatch(setDiagnostics(view.state, parseCelErrors(result.error)));
        }
      } catch {
        // 网络/服务端异常静默（不阻塞编辑）
      }
    }, 300);
  }, [tenantId, sceneCode]);

  // 初始化只执行一次
  useEffect(() => {
    if (!containerRef.current || viewRef.current) return;

    const state = EditorState.create({
      doc: script?.source ?? '',
      extensions: [
        basicSetup,
        oneDark,
        lintGutter(),
        languageCompartment.of(langExtension(lang)),
        autocompleteCompartment.of(autocompletion({ override: [completeFn] })),
        EditorView.theme({
          '&': { height: '400px' },
          '.cm-scroller': { overflow: 'auto' },
        }),
        EditorView.updateListener.of((update) => {
          if (update.docChanged && !updatingFromOutside.current) {
            onChange({ lang: langRef.current, source: update.state.doc.toString(), params: paramsRef.current });
            runValidate(update.view, update.state.doc.toString(), langRef.current);
          }
        }),
      ],
    });

    viewRef.current = new EditorView({ state, parent: containerRef.current });
    return () => { viewRef.current?.destroy(); viewRef.current = null; };
  }, []);

  useEffect(() => {
    paramsRef.current = script?.params;
  }, [script?.params]);

  useEffect(() => {
    langRef.current = lang;
    viewRef.current?.dispatch({
      effects: languageCompartment.reconfigure(langExtension(lang)),
    });
  }, [lang]);

  useEffect(() => {
    viewRef.current?.dispatch({
      effects: autocompleteCompartment.reconfigure(autocompletion({ override: [completeFn] })),
    });
  }, [completeFn]);

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
