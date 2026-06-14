import { useEffect, useState } from 'react';
import { Button, Select, Typography, Tag } from 'antd';
import { PlusOutlined, DeleteOutlined, SwapOutlined } from '@ant-design/icons';
import { useTenantStore } from '@/store/tenantStore';
import { listDecisions } from '@/api/decision';
import type { AstNode, IfNode, DecisionLeafNode, ConditionTypeMeta, MetricDescriptor } from '@/types';
import GroupEditor from './GroupEditor';

interface Props {
  ast: IfNode | null;
  conditionTypes: ConditionTypeMeta[];
  availableMetrics: MetricDescriptor[];
  payloadFieldNames: string[];
  onChange: (node: IfNode) => void;
}

function emptyLeaf(): DecisionLeafNode {
  return { type: 'DecisionLeafNode', decisionCode: '', category: null };
}

function emptyIf(): IfNode {
  return { type: 'IfNode', condition: { type: 'AndNode' as const, children: [] }, thenBranch: emptyLeaf(), elseBranch: null };
}

export default function DecisionTreeEditor({
  ast, conditionTypes, availableMetrics, payloadFieldNames, onChange,
}: Props) {
  const { currentId } = useTenantStore();
  const [decisions, setDecisions] = useState<{ value: string; label: string }[]>([]);

  useEffect(() => {
    if (currentId) listDecisions(currentId).then((d) => {
      setDecisions((d.data ?? []).map((item) => ({ value: item.code, label: `${item.code} (${item.name})` })));
    });
  }, [currentId]);

  const root: IfNode = ast?.type === 'IfNode' ? ast : emptyIf();

  /** 递归渲染一个 IfNode */
  const renderIfNode = (node: IfNode, setNode: (n: IfNode) => void, depth: number): React.ReactNode => {
    const thenIsIf = node.thenBranch?.type === 'IfNode';
    const elseIsIf = node.elseBranch?.type === 'IfNode';
    const elseIsNull = !node.elseBranch;

    // 条件区域归一化为 group
    const conditionGroup = (node.condition?.type === 'AndNode' || node.condition?.type === 'OrNode' || node.condition?.type === 'NotNode')
      ? node.condition
      : node.condition?.type === 'ConditionNode'
        ? { type: 'AndNode' as const, children: [node.condition] }
        : { type: 'AndNode' as const, children: [] as AstNode[] };

    return (
      <div key={depth} style={{ marginBottom: 12 }}>
        {/* 条件区域 */}
        <div style={{ marginLeft: depth * 24, marginBottom: 8 }}>
          <div style={{ fontSize: 12, color: '#999', marginBottom: 4 }}>条件：</div>
          <GroupEditor
            node={conditionGroup as never}
            conditionTypes={conditionTypes}
            availableMetrics={availableMetrics}
            payloadFieldNames={payloadFieldNames}
            onChange={(c) => setNode({ ...node, condition: c })}
          />
        </div>

        {/* THEN 分支 */}
        <div style={{ marginLeft: depth * 24, marginBottom: 4 }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
            <Tag color="blue" style={{ margin: 0 }}>THEN</Tag>
            {node.thenBranch?.type === 'DecisionLeafNode' ? (
              <Select
                size="small"
                showSearch
                style={{ width: 200 }}
                value={(node.thenBranch as DecisionLeafNode).decisionCode || undefined}
                onChange={(code) => setNode({
                  ...node,
                  thenBranch: { type: 'DecisionLeafNode', decisionCode: code, category: null },
                })}
                options={decisions}
                placeholder="选择 Decision"
              />
            ) : null}
            <Button size="small" icon={<SwapOutlined />}
              onClick={() => setNode({
                ...node,
                thenBranch: thenIsIf ? emptyLeaf() : emptyIf(),
              })}>
              {thenIsIf ? '改为决策' : '改为分支'}
            </Button>
          </div>
        </div>
        {thenIsIf && renderIfNode(node.thenBranch as IfNode, (n) => setNode({ ...node, thenBranch: n }), depth + 1)}

        {/* ELSE 分支 */}
        <div style={{ marginLeft: depth * 24 }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
            <Tag color="orange" style={{ margin: 0 }}>ELSE</Tag>
            {elseIsNull ? (
              <Button size="small" type="dashed" icon={<PlusOutlined />}
                onClick={() => setNode({ ...node, elseBranch: emptyLeaf() })}>
                添加 ELSE
              </Button>
            ) : (
              <>
                {node.elseBranch?.type === 'DecisionLeafNode' && (
                  <Select
                    size="small"
                    showSearch
                    style={{ width: 200 }}
                    value={(node.elseBranch as DecisionLeafNode).decisionCode || undefined}
                    onChange={(code) => setNode({
                      ...node,
                      elseBranch: { type: 'DecisionLeafNode', decisionCode: code, category: null },
                    })}
                    options={decisions}
                    placeholder="选择 Decision"
                  />
                )}
                <Button size="small" icon={<DeleteOutlined />} danger
                  onClick={() => setNode({ ...node, elseBranch: null })} />
                <Button size="small" icon={<SwapOutlined />}
                  onClick={() => setNode({
                    ...node,
                    elseBranch: elseIsIf ? emptyLeaf() : emptyIf(),
                  })}>
                  {elseIsIf ? '改为决策' : '改为分支'}
                </Button>
              </>
            )}
          </div>
        </div>
        {elseIsIf && renderIfNode(node.elseBranch as IfNode, (n) => setNode({ ...node, elseBranch: n }), depth + 1)}
      </div>
    );
  };

  return (
    <div style={{ padding: 8 }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 16 }}>
        <Typography.Text strong>决策树</Typography.Text>
      </div>
      {renderIfNode(root, onChange, 0)}
    </div>
  );
}
