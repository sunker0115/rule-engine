import { useEffect, useRef } from 'react';
import { Typography } from 'antd';
import { useTranslation } from 'react-i18next';
import { diff } from 'jsondiffpatch';

interface JsonDiffViewerProps {
  before?: Record<string, unknown>;
  after?: Record<string, unknown>;
}

/**
 * 使用 jsondiffpatch 渲染变更前/后 JSON 的差异视图。
 * 若库加载失败或数据不存在，降级为并排 <pre> 代码块。
 */
export default function JsonDiffViewer({ before, after }: JsonDiffViewerProps) {
  const { t } = useTranslation('audit');
  const containerRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!before || !after || !containerRef.current) return;
    // cancelled 守卫:异步 formatter 解析前 props 已切换时丢弃旧结果，避免把上一行的 diff 写入容器（竞态）
    let cancelled = false;
    try {
      const delta = diff(before, after);
      if (!delta) {
        containerRef.current.innerHTML = `<div style="color:#999;padding:8px">${t('diff.noDiff')}</div>`;
        return;
      }
      // 动态引入 html formatter 渲染
      import('jsondiffpatch/formatters/html').then(({ format: htmlFormat }) => {
        if (!cancelled && containerRef.current) {
          containerRef.current.innerHTML = htmlFormat(delta, before) ?? `<div style="color:#999;padding:8px">${t('diff.noDiff')}</div>`;
        }
      }).catch(() => {
        if (!cancelled && containerRef.current) {
          containerRef.current.innerHTML = `<div style="color:red;padding:8px">${t('diff.renderError')}</div>`;
        }
      });
    } catch {
      if (containerRef.current) {
        containerRef.current.innerHTML = `<div style="color:red;padding:8px">${t('diff.calcError')}</div>`;
      }
    }
    return () => { cancelled = true; };
  }, [before, after, t]);

  // 降级：side-by-side JSON 展示
  if (!before && !after) {
    return <div style={{ color: '#999' }}>{t('diff.noSnapshot')}</div>;
  }

  return (
    <div>
      {before ? (
        <div ref={containerRef} style={{ maxHeight: 400, overflow: 'auto', fontSize: 12 }} />
      ) : (
        <div style={{ display: 'flex', gap: 16 }}>
          <div style={{ flex: 1 }}>
            <Typography.Text strong>{t('diff.before')}</Typography.Text>
            <pre style={{ fontSize: 11, background: '#f5f5f5', padding: 8, maxHeight: 300, overflow: 'auto' }}>
              {JSON.stringify(before, null, 2)}
            </pre>
          </div>
          <div style={{ flex: 1 }}>
            <Typography.Text strong>{t('diff.after')}</Typography.Text>
            <pre style={{ fontSize: 11, background: '#f5f5f5', padding: 8, maxHeight: 300, overflow: 'auto' }}>
              {JSON.stringify(after, null, 2)}
            </pre>
          </div>
        </div>
      )}
    </div>
  );
}
