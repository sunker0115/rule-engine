import { useState, useEffect } from 'react';
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

/** flow 编排节点 tag → antd Tag 颜色（对齐画布配色：RuleRef 蓝 / Switch 紫 / Transform 青 / Output 绿）。 */
const FLOW_TAG_COLOR: Record<string, string> = {
  RuleRefNode: 'blue',
  SwitchNode: 'purple',
  TransformNode: 'cyan',
  OutputNode: 'green',
};

/** 是否为 DECISION_FLOW 图的编排节点（trace 的 nodeType == FlowNodeType.tag）。 */
function isFlowNode(nodeType: string): boolean {
  return nodeType in FLOW_TAG_COLOR;
}

function resultIcon(node: NodeTraceItem): string {
  // flow 编排节点（Switch/Transform 等）result 恒 null，但语义是「已执行的编排步骤」而非「短路跳过」，
  // 故不用 ⏭ skip 图标；有明确 true/false 的（如 RuleRef 命中）仍按结果显示。
  if (node.result === null && isFlowNode(node.nodeType)) return '▸';
  return RESULT_ICON[String(node.result)] ?? '⚠️';
}

function resultLabel(node: NodeTraceItem, t: (key: string) => string): string {
  if (node.result === null && isFlowNode(node.nodeType)) return t('trace.nodeFlowStep');
  if (node.result === true) return t('trace.nodeSatisfied');
  if (node.result === false) return t('trace.nodeUnsatisfied');
  if (node.result === null) return t('trace.nodeSkipped');
  return t('trace.nodeError');
}

// NodeTraceItem 无自带 id，用渲染路径（父 key + 子序号）作稳定 key
function collectKeys(nodes: NodeTraceItem[], prefix = '', acc: string[] = []): string[] {
  nodes.forEach((node, i) => {
    const key = prefix ? `${prefix}-${i}` : `${i}`;
    if ((node.children?.length ?? 0) > 0) {
      acc.push(key);
      collectKeys(node.children, key, acc);
    }
  });
  return acc;
}

function TraceNode({
  node,
  depth,
  nodeKey,
  expandedKeys,
  toggle,
}: {
  node: NodeTraceItem;
  depth: number;
  nodeKey: string;
  expandedKeys: Set<string>;
  toggle: (key: string) => void;
}) {
  const { t } = useTranslation('eval');
  const hasChildren = (node.children?.length ?? 0) > 0;
  const expanded = expandedKeys.has(nodeKey);

  return (
    <div>
      <div
        style={{ marginLeft: depth * 24, padding: '4px 0', display: 'flex', alignItems: 'center', gap: 6 }}
        onClick={() => hasChildren && toggle(nodeKey)}
      >
        {hasChildren && (
          expanded ? <CaretDownOutlined style={{ fontSize: 10 }} /> : <CaretRightOutlined style={{ fontSize: 10 }} />
        )}
        {!hasChildren && <span style={{ width: 10 }} />}
        <span title={resultLabel(node, t)}>{resultIcon(node)}</span>
        <Tag color={FLOW_TAG_COLOR[node.nodeType]}>{node.nodeType}</Tag>
        {node.metricCode && <Tag color="blue">{node.metricCode}</Tag>}
        {node.actualValue !== undefined && (
          <span style={{ color: '#666', marginLeft: 8 }}>= {JSON.stringify(node.actualValue)}</span>
        )}
        {node.valueSource && <Tag>{node.valueSource}</Tag>}
        {node.errorCode && <Tag color="red">{node.errorCode}</Tag>}
        {node.errorMessage && <span style={{ color: 'red', fontSize: 12 }}>{node.errorMessage}</span>}
      </div>
      {hasChildren && expanded && node.children.map((child, i) => (
        <TraceNode
          key={i}
          node={child}
          depth={depth + 1}
          nodeKey={`${nodeKey}-${i}`}
          expandedKeys={expandedKeys}
          toggle={toggle}
        />
      ))}
    </div>
  );
}

export default function TraceTree({ nodes }: TraceTreeProps) {
  const { t } = useTranslation('eval');
  // 受控展开：默认全部展开
  const [expandedKeys, setExpandedKeys] = useState<Set<string>>(() => new Set(collectKeys(nodes ?? [])));

  // nodes 变化（切换 session / 重跑 dry-run）时重置为默认全展开；惰性初始化只在 mount 跑一次，不会自更新
  useEffect(() => {
    setExpandedKeys(new Set(collectKeys(nodes ?? [])));
  }, [nodes]);

  const toggle = (key: string) => {
    setExpandedKeys((prev) => {
      const next = new Set(prev);
      if (next.has(key)) next.delete(key);
      else next.add(key);
      return next;
    });
  };

  const expandAll = () => setExpandedKeys(new Set(collectKeys(nodes ?? [])));
  const collapseAll = () => setExpandedKeys(new Set());

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
        <Button size="small" onClick={collapseAll}>{t('trace.collapseAll')}</Button>
        <Button size="small" onClick={handleCopyJson}>{t('trace.copyJson')}</Button>
      </Space>
      {nodes.map((node, i) => (
        <TraceNode
          key={i}
          node={node}
          depth={0}
          nodeKey={`${i}`}
          expandedKeys={expandedKeys}
          toggle={toggle}
        />
      ))}
    </div>
  );
}
