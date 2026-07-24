import { describe, it, expect, vi } from 'vitest';
import { render } from '@testing-library/react';
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
