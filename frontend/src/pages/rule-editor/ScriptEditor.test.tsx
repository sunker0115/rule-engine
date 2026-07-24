import { describe, it, expect, vi } from 'vitest';
import { render, fireEvent, within } from '@testing-library/react';
import { EditorView } from '@codemirror/view';
import ScriptEditor from './ScriptEditor';

describe('ScriptEditor updateListener 保留 params', () => {
  it('源码编辑触发的 onChange 仍带原 params', () => {
    const onChange = vi.fn();
    const { container } = render(
      <ScriptEditor
        script={{ source: 'a > 1', lang: 'CEL', params: { threshold: 1 } }}
        onChange={onChange}
        availableMetrics={[]}
        payloadFieldNames={[]}
      />,
    );

    // 通过 DOM 拿到内部 CodeMirror view，直接 dispatch 一个文档变更模拟"源码编辑"
    const view = EditorView.findFromDOM(container.querySelector('.cm-editor') as HTMLElement);
    expect(view).not.toBeNull();
    view!.dispatch({ changes: { from: view!.state.doc.length, insert: ' + 2' } });

    expect(onChange).toHaveBeenCalled();
    const calls = onChange.mock.calls;
    const payload = calls[calls.length - 1][0];
    expect(payload.source).toBe('a > 1 + 2');
    // 核心断言：keystroke 后 params 未被 updateListener 闭包丢弃
    expect(payload.params).toEqual({ threshold: 1 });
  });
});

describe('ScriptEditor 参数表', () => {
  it('editableParams=true 渲染参数表，且不受影响的只读断言不适用', () => {
    const onChange = vi.fn();
    const { getByText, getByDisplayValue } = render(
      <ScriptEditor
        script={{ source: 'a > 1', lang: 'CEL', params: { threshold: 1 } }}
        onChange={onChange}
        availableMetrics={[]}
        payloadFieldNames={[]}
        editableParams
      />,
    );
    // 表格渲染出参数名输入 + 底部添加按钮
    expect(getByDisplayValue('threshold')).toBeTruthy();
    expect(getByText('添加参数')).toBeTruthy();
  });

  it('+ 添加参数 增行 → onChange 带新 params，且仍带 source+lang', () => {
    const onChange = vi.fn();
    const { getByText } = render(
      <ScriptEditor
        script={{ source: 'a > 1', lang: 'CEL', params: { threshold: 1 } }}
        onChange={onChange}
        availableMetrics={[]}
        payloadFieldNames={[]}
        editableParams
      />,
    );
    fireEvent.click(getByText('添加参数'));
    expect(onChange).toHaveBeenCalled();
    const payload = onChange.mock.calls[onChange.mock.calls.length - 1][0];
    expect(payload.source).toBe('a > 1');
    expect(payload.lang).toBe('CEL');
    expect(payload.params).toEqual({ threshold: 1, param1: '' });
  });

  it('参数化 Switch 点击 → onParamSlotToggle(key, enabled, dataType)', () => {
    const onChange = vi.fn();
    const onParamSlotToggle = vi.fn();
    const { getByRole } = render(
      <ScriptEditor
        script={{ source: 'a > 1', lang: 'CEL', params: { threshold: 1 } }}
        onChange={onChange}
        availableMetrics={[]}
        payloadFieldNames={[]}
        editableParams
        onParamSlotToggle={onParamSlotToggle}
      />,
    );
    // 唯一的 switch 即参数化列（threshold 推断为 LONG → 默认值格是 InputNumber 而非 Switch）
    fireEvent.click(getByRole('switch'));
    expect(onParamSlotToggle).toHaveBeenCalledWith('threshold', true, 'LONG');
  });

  it('editableParams=false → 只读展示，无增删控件', () => {
    const onChange = vi.fn();
    const { queryByText, getByText } = render(
      <ScriptEditor
        script={{ source: 'a > 1', lang: 'CEL', params: { threshold: 1 } }}
        onChange={onChange}
        availableMetrics={[]}
        payloadFieldNames={[]}
      />,
    );
    expect(queryByText('添加参数')).toBeNull();
    // Descriptions 只读展示 名=值
    const label = getByText('threshold');
    expect(within(label.closest('tr') as HTMLElement).getByText('1')).toBeTruthy();
  });
});
