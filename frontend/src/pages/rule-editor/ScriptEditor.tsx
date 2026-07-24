import { useEffect, useRef, useMemo, useCallback, useState } from 'react';
import { Tag, Table, Input, Select, Switch, Button, Descriptions, Empty } from 'antd';
import type { TableColumnsType } from 'antd';
import { PlusOutlined, DeleteOutlined } from '@ant-design/icons';
import { EditorView, basicSetup } from 'codemirror';
import { oneDark } from '@codemirror/theme-one-dark';
import { EditorState, Compartment } from '@codemirror/state';
import { javascript } from '@codemirror/lang-javascript';
import { json } from '@codemirror/lang-json';
import { autocompletion, type CompletionContext } from '@codemirror/autocomplete';
import { lintGutter, setDiagnostics, type Diagnostic } from '@codemirror/lint';
import type { MetricDescriptor, ScriptParams } from '@/types';
import type { DataType } from '@/types/template';
import SlotValueInput from '@/components/SlotValueInput';
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
  /** true 时参数表可增删改（模板编辑场景）；缺省 false 走只读展示（规则编辑场景）。 */
  editableParams?: boolean;
  /** 参数化开关回调——参数表↔模板 slots/bindings 的接线契约；未传时不渲染参数化列。 */
  onParamSlotToggle?: (key: string, enabled: boolean, dataType: DataType) => void;
}

const DATA_TYPES: DataType[] = ['LONG', 'DOUBLE', 'DECIMAL', 'STRING', 'BOOLEAN', 'DATE', 'DATETIME', 'LIST'];

/** 从已有 params 值推断 UI 类型（仅编辑期展示用，不入 body）。 */
function inferDataType(value: unknown): DataType {
  if (typeof value === 'boolean') return 'BOOLEAN';
  if (typeof value === 'number') return Number.isInteger(value) ? 'LONG' : 'DOUBLE';
  if (Array.isArray(value)) return 'LIST';
  return 'STRING';
}

/** 以插入序重建 params，按需重命名某个 key（保留顺序与值）。 */
function renameKey(params: ScriptParams, oldKey: string, newKey: string): ScriptParams {
  const next: ScriptParams = {};
  for (const [k, v] of Object.entries(params)) {
    next[k === oldKey ? newKey : k] = v;
  }
  return next;
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

export default function ScriptEditor({ script, onChange, availableMetrics, payloadFieldNames, payloadFieldTypes, tenantId, sceneCode, editableParams = false, onParamSlotToggle }: Props) {
  const containerRef = useRef<HTMLDivElement>(null);
  const viewRef = useRef<EditorView | null>(null);
  const langRef = useRef(script?.lang ?? 'CEL');
  // updateListener 闭包在空依赖 init effect 内，直接读 script?.params 会读到旧值；
  // 用 ref 随 params 同步，onChange 时补回当前 params，避免每次编辑丢参数
  const paramsRef = useRef<ScriptParams | undefined>(script?.params);
  const updatingFromOutside = useRef(false);

  const lang = script?.lang ?? 'CEL';
  const source = script?.source ?? '';
  const params = script?.params ?? {};
  const types = payloadFieldTypes ?? {};

  // dataType 与参数化开关态均为 UI-only（不入 script.params，只存值）
  const [typeMap, setTypeMap] = useState<Record<string, DataType>>({});
  const [slotMap, setSlotMap] = useState<Record<string, boolean>>({});
  const dataTypeOf = (key: string) => typeMap[key] ?? inferDataType(params[key]);

  const paramKeys = useMemo(() => Object.keys(script?.params ?? {}), [script?.params]);

  const completeFn = useMemo(
    () => (ctx: CompletionContext) => expressionCompletions(ctx, availableMetrics, payloadFieldNames, types, paramKeys),
    [availableMetrics, payloadFieldNames, types, paramKeys],
  );

  // 参数表编辑：改 params 也必须走带 source+lang+params 的完整 onChange（与 updateListener 一致，不丢字段）
  const emitParams = useCallback(
    (next: ScriptParams) => onChange({ source, lang, params: next }),
    [onChange, source, lang],
  );

  const handleValueChange = (key: string, value: unknown) => emitParams({ ...params, [key]: value });

  const handleKeyRename = (oldKey: string, newKey: string) => {
    if (!newKey || newKey === oldKey || Object.prototype.hasOwnProperty.call(params, newKey)) return;
    setTypeMap((m) => (m[oldKey] === undefined ? m : { ...m, [newKey]: m[oldKey] }));
    setSlotMap((m) => (m[oldKey] === undefined ? m : { ...m, [newKey]: m[oldKey] }));
    emitParams(renameKey(params, oldKey, newKey));
  };

  const handleTypeChange = (key: string, dt: DataType) => setTypeMap((m) => ({ ...m, [key]: dt }));

  const handleDelete = (key: string) => {
    const next = { ...params };
    delete next[key];
    emitParams(next);
  };

  const handleAdd = () => {
    let i = 1;
    while (Object.prototype.hasOwnProperty.call(params, `param${i}`)) i += 1;
    emitParams({ ...params, [`param${i}`]: '' });
  };

  const handleSlotToggle = (key: string, enabled: boolean) => {
    setSlotMap((m) => ({ ...m, [key]: enabled }));
    onParamSlotToggle?.(key, enabled, dataTypeOf(key));
  };

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

  const entries = Object.entries(params);
  type Row = { key: string; value: unknown };
  const paramColumns: TableColumnsType<Row> = [
    {
      title: '参数名',
      render: (_: unknown, row: Row) => (
        <Input defaultValue={row.key} onBlur={(e) => handleKeyRename(row.key, e.target.value.trim())} />
      ),
    },
    {
      title: '类型',
      render: (_: unknown, row: Row) => (
        <Select
          style={{ width: 120 }}
          value={dataTypeOf(row.key)}
          onChange={(dt) => handleTypeChange(row.key, dt)}
          options={DATA_TYPES.map((dt) => ({ label: dt, value: dt }))}
        />
      ),
    },
    {
      title: '默认值',
      render: (_: unknown, row: Row) => (
        <SlotValueInput dataType={dataTypeOf(row.key)} value={row.value} onChange={(v) => handleValueChange(row.key, v)} />
      ),
    },
    ...(onParamSlotToggle
      ? [{
          title: '参数化',
          render: (_: unknown, row: Row) => (
            <Switch checked={!!slotMap[row.key]} onChange={(enabled) => handleSlotToggle(row.key, enabled)} />
          ),
        } as TableColumnsType<Row>[number]]
      : []),
    {
      title: '',
      width: 48,
      render: (_: unknown, row: Row) => (
        <Button type="text" size="small" danger icon={<DeleteOutlined />} onClick={() => handleDelete(row.key)} />
      ),
    },
  ];

  return (
    <div style={{ padding: 8 }}>
      <div style={{ marginBottom: 8 }}>
        <Tag color="blue">{lang}</Tag>
      </div>
      <div ref={containerRef} style={{ border: '1px solid #d9d9d9', borderRadius: 6, overflow: 'hidden', height: 400 }} />

      <div style={{ marginTop: 12 }}>
        <div style={{ marginBottom: 8, fontWeight: 500 }}>参数（params.&lt;键&gt;）</div>
        {editableParams ? (
          <>
            <Table<Row>
              size="small"
              rowKey="key"
              pagination={false}
              dataSource={entries.map(([key, value]) => ({ key, value }))}
              locale={{ emptyText: <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无参数" /> }}
              columns={paramColumns}
            />
            <Button type="dashed" icon={<PlusOutlined />} onClick={handleAdd} block style={{ marginTop: 8 }}>
              添加参数
            </Button>
          </>
        ) : (
          entries.length === 0
            ? <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无参数" />
            : (
              <Descriptions size="small" column={1} bordered>
                {entries.map(([key, value]) => (
                  <Descriptions.Item key={key} label={key}>{String(value)}</Descriptions.Item>
                ))}
              </Descriptions>
            )
        )}
      </div>
    </div>
  );
}
