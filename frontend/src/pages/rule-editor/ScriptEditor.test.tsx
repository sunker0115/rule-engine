import { useState } from 'react';
import { describe, it, expect, vi } from 'vitest';
import { render, fireEvent, within, act } from '@testing-library/react';
import { EditorView } from '@codemirror/view';
import type { ScriptParams } from '@/types';
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

  // 受控回填：重开已有脚本模板时开关态须由 slottedParamKeys（模板 bindings 真相源）驱动，
  // 不再是内部 state 恒空导致全 OFF。
  it('参数化开关 checked 受控于 slottedParamKeys（重开回填）', () => {
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
        slottedParamKeys={['threshold']}
      />,
    );
    expect(getByRole('switch')).toBeChecked();
  });

  // 改名传播：已参数化的键改名 → 通知模板先摘旧 slot(old,false) 再加新 slot(new,true)，
  // 使 binding 从 /script/params/<old> 迁到 <new>，修陈旧指针数据 bug。
  it('已参数化的 param 改名 → onParamSlotToggle(old,false) 后 (new,true)', () => {
    const onChange = vi.fn();
    const onParamSlotToggle = vi.fn();
    const { getByDisplayValue } = render(
      <ScriptEditor
        script={{ source: 'a > 1', lang: 'CEL', params: { threshold: 1 } }}
        onChange={onChange}
        availableMetrics={[]}
        payloadFieldNames={[]}
        editableParams
        onParamSlotToggle={onParamSlotToggle}
        slottedParamKeys={['threshold']}
      />,
    );
    const input = getByDisplayValue('threshold');
    fireEvent.change(input, { target: { value: 'newThreshold' } });
    fireEvent.blur(input);
    expect(onParamSlotToggle.mock.calls).toEqual([
      ['threshold', false, 'LONG'],
      ['newThreshold', true, 'LONG'],
    ]);
  });

  // 受控父组件回归：锁死双源 cross-overwrite。
  // 两个 onChange 源——CodeMirror updateListener（读 paramsRef，由 useEffect([script?.params]) 同步）
  // 与参数表 emitParams（读 props.source）——在受控父下必须都拿到最新值：
  // 参数表编辑 → props 更新 → paramsRef 刷新 → 源码编辑读到新 params（不覆盖回旧值），反之亦然。
  it('受控父下：参数表编辑后源码编辑不丢新 params（paramsRef 同步 + 源码不被覆盖）', () => {
    let latest: { source: string; lang: string; params?: ScriptParams } = {
      source: 'metrics.x > 1',
      lang: 'CEL',
      params: { threshold: 1 },
    };
    function Wrapper() {
      const [s, setS] = useState<{ source: string; lang: string; params?: ScriptParams }>(latest);
      latest = s;
      return (
        <ScriptEditor
          script={s}
          onChange={setS}
          availableMetrics={[]}
          payloadFieldNames={[]}
          editableParams
        />
      );
    }

    const { getByText, container } = render(<Wrapper />);

    // 1. 参数表编辑：新增一个参数 → 受控父 state.params 更新为 {threshold:1, param1:''}
    act(() => {
      fireEvent.click(getByText('添加参数'));
    });
    expect(latest.params).toEqual({ threshold: 1, param1: '' });
    expect(latest.source).toBe('metrics.x > 1');

    // 2. 源码编辑：通过内部 CodeMirror view dispatch 一个文档变更模拟 keystroke
    const view = EditorView.findFromDOM(container.querySelector('.cm-editor') as HTMLElement);
    expect(view).not.toBeNull();
    act(() => {
      view!.dispatch({ changes: { from: view!.state.doc.length, insert: ' + 2' } });
    });

    // 3. 最终受控状态同时带「新 source」与「新 params」：
    //    源码编辑没有用旧闭包 params 覆盖参数表新增（paramsRef 已同步）
    expect(latest.source).toBe('metrics.x > 1 + 2');
    expect(latest.params).toEqual({ threshold: 1, param1: '' });
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
