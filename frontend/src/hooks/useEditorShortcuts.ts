import { useEffect, useRef } from 'react';

interface UseEditorShortcutsParams {
  /** 当前是否可保存（draft + dirty） */
  canSave: boolean;
  /** 保存回调 */
  onSave: () => void;
  /** 撤回 */
  onUndo: () => void;
  /** 重做 */
  onRedo: () => void;
}

/**
 * 全局编辑器快捷键（所有 rule kind 通用）：
 * Ctrl/Cmd+S → 保存草稿  |  Ctrl/Cmd+Z → 撤回  |  Ctrl/Cmd+Shift+Z → 重做
 */
export function useEditorShortcuts({ canSave, onSave, onUndo, onRedo }: UseEditorShortcutsParams) {
  const canSaveRef = useRef(canSave);
  canSaveRef.current = canSave;
  const onSaveRef = useRef(onSave);
  onSaveRef.current = onSave;

  useEffect(() => {
    const handler = (e: KeyboardEvent) => {
      const mod = e.metaKey || e.ctrlKey;
      if (mod && e.key === 's') {
        e.preventDefault();
        if (canSaveRef.current) onSaveRef.current();
      } else if (mod && e.shiftKey && e.key === 'z') {
        e.preventDefault();
        onRedo();
      } else if (mod && !e.shiftKey && e.key === 'z') {
        e.preventDefault();
        onUndo();
      }
    };
    window.addEventListener('keydown', handler);
    return () => window.removeEventListener('keydown', handler);
  }, [onUndo, onRedo]);
}
