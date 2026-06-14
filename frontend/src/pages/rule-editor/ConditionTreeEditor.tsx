import { Button, Empty } from 'antd';
import { PlusOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import type { AstNode, AndNode, OrNode, NotNode, ConditionTypeMeta, MetricDescriptor } from '@/types';
import GroupEditor from './GroupEditor';

interface Props {
  ast: AstNode | null;
  conditionTypes: ConditionTypeMeta[];
  availableMetrics: MetricDescriptor[];
  payloadFieldNames: string[];
  onChange: (ast: AstNode) => void;
}

/** 将 AstNode 归一化为 AndNode 或 OrNode（顶层必须是组） */
function normalizeToGroup(node: AstNode): AndNode | OrNode | NotNode {
  if (node.type === 'AndNode' || node.type === 'OrNode' || node.type === 'NotNode') {
    return node;
  }
  // 裸 ConditionNode → 包裹为单元素 AndNode
  return { type: 'AndNode', children: [node] };
}

export default function ConditionTreeEditor({
  ast, conditionTypes, availableMetrics, payloadFieldNames, onChange,
}: Props) {
  const { t } = useTranslation('rule');
  const group = ast ? normalizeToGroup(ast) : null;
  const isEmpty = group === null ||
    ((group.type === 'AndNode' || group.type === 'OrNode') && group.children.length === 0);

  if (isEmpty) {
    return (
      <div style={{ padding: 40, textAlign: 'center' }}>
        <Empty description={t('editor.conditionTree.emptyHint')}>
          <Button
            type="primary"
            icon={<PlusOutlined />}
            onClick={() => onChange({ type: 'AndNode', children: [] })}
          >
            {t('editor.conditionTree.addFirst')}
          </Button>
        </Empty>
      </div>
    );
  }

  return (
    <GroupEditor
      node={group}
      conditionTypes={conditionTypes}
      availableMetrics={availableMetrics}
      payloadFieldNames={payloadFieldNames}
      onChange={onChange}
    />
  );
}
