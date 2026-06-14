import { useState } from 'react';
import { Button, Space, Tag, message } from 'antd';
import { CaretDownOutlined, CaretRightOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import type { NodeTraceItem } from '@/types';

interface TraceTreeProps {
  nodes: NodeTraceItem[];
}

const RESULT_ICON: Record<string, string> = {
  true: '✅',
  false: '❌',
  null: '⏭',
};

function resultIcon(result: boolean | null): string {
  return RESULT_ICON[String(result)] ?? '⚠️';
}

function resultLabel(result: boolean | null, t: (key: string) => string): string {
  if (result === true) return t('trace.nodeSatisfied');
  if (result === false) return t('trace.nodeUnsatisfied');
  if (result === null) return t('trace.nodeSkipped');
  return t('trace.nodeError');
}

function TraceNode({ node, depth }: { node: NodeTraceItem; depth: number }) {
  const { t } = useTranslation('eval');
  const [collapsed, setCollapsed] = useState(false);
  const hasChildren = (node.children?.length ?? 0) > 0;

  return (
    <div>
      <div
        style={{ marginLeft: depth * 24, padding: '4px 0', display: 'flex', alignItems: 'center', gap: 6 }}
        onClick={() => hasChildren && setCollapsed(!collapsed)}
      >
        {hasChildren && (
          collapsed ? <CaretRightOutlined style={{ fontSize: 10 }} /> : <CaretDownOutlined style={{ fontSize: 10 }} />
        )}
        {!hasChildren && <span style={{ width: 10 }} />}
        <span title={resultLabel(node.result, t)}>{resultIcon(node.result)}</span>
        <Tag>{node.nodeType}</Tag>
        {node.metricCode && <Tag color="blue">{node.metricCode}</Tag>}
        {node.actualValue !== undefined && (
          <span style={{ color: '#666', marginLeft: 8 }}>= {JSON.stringify(node.actualValue)}</span>
        )}
        {node.valueSource && <Tag>{node.valueSource}</Tag>}
        {node.errorCode && <Tag color="red">{node.errorCode}</Tag>}
        {node.errorMessage && <span style={{ color: 'red', fontSize: 12 }}>{node.errorMessage}</span>}
      </div>
      {hasChildren && !collapsed && node.children!.map((child, i) => (
        <TraceNode key={i} node={child} depth={depth + 1} />
      ))}
    </div>
  );
}

export default function TraceTree({ nodes }: TraceTreeProps) {
  const { t } = useTranslation('eval');

  const expandAll = () => {
    // Force re-render by toggling key — simple approach
    message.info(t('trace.expandAll'));
  };

  const handleCopyJson = () => {
    navigator.clipboard.writeText(JSON.stringify(nodes, null, 2)).then(() => {
      message.success(t('trace.copyJson'));
    });
  };

  if (!nodes || nodes.length === 0) {
    return <div style={{ color: '#999' }}>{t('trace.noData')}</div>;
  }

  return (
    <div>
      <Space style={{ marginBottom: 12 }}>
        <Button size="small" onClick={expandAll}>{t('trace.expandAll')}</Button>
        <Button size="small">{t('trace.collapseAll')}</Button>
        <Button size="small" onClick={handleCopyJson}>{t('trace.copyJson')}</Button>
      </Space>
      {nodes.map((node, i) => (
        <TraceNode key={i} node={node} depth={0} />
      ))}
    </div>
  );
}
