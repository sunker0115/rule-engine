import { Button, Select, Typography } from 'antd';
import { PlusOutlined, ExceptionOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import type { AstNode, AndNode, OrNode, NotNode, ConditionNode, ConditionTypeMeta, MetricDescriptor } from '@/types';
import ConditionCard from './ConditionCard';

interface Props {
  node: AndNode | OrNode | NotNode;
  conditionTypes: ConditionTypeMeta[];
  availableMetrics: MetricDescriptor[];
  payloadFieldNames: string[];
  onChange: (node: AstNode) => void;
  onDelete?: () => void;
}

/** 创建空 ConditionNode */
function emptyCondition(): ConditionNode {
  return {
    type: 'ConditionNode',
    conditionType: '',
    params: {},
  };
}

/** 创建空 AndGroup */
function emptyGroup(): AndNode {
  return { type: 'AndNode', children: [] };
}

export default function GroupEditor({
  node, conditionTypes, availableMetrics, payloadFieldNames, onChange, onDelete,
}: Props) {
  const { t } = useTranslation('rule');

  // NotNode 包裹层
  if (node.type === 'NotNode') {
    const child = node.child;
    return (
      <div style={{ border: '1px dashed #ff4d4f', borderRadius: 6, padding: '8px 12px', marginBottom: 4 }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 4 }}>
          <Typography.Text type="danger" strong style={{ fontSize: 12 }}>{t('editor.groupEditor.not')}</Typography.Text>
          <div style={{ flex: 1 }} />
          <Button type="text" size="small" onClick={() => onChange(child)}>{t('editor.groupEditor.unNot')}</Button>
          {onDelete && <Button type="text" size="small" danger onClick={onDelete}>{t('editor.groupEditor.deleteGroup')}</Button>}
        </div>
        {child.type === 'ConditionNode' ? (
          <ConditionCard
            node={child}
            conditionTypes={conditionTypes}
            availableMetrics={availableMetrics}
            payloadFieldNames={payloadFieldNames}
            onChange={(n) => onChange({ ...node, child: n })}
            onDelete={() => onChange({ type: 'AndNode', children: [] })}
          />
        ) : (
          <GroupEditor
            node={child as AndNode | OrNode}
            conditionTypes={conditionTypes}
            availableMetrics={availableMetrics}
            payloadFieldNames={payloadFieldNames}
            onChange={(n) => onChange({ ...node, child: n })}
          />
        )}
      </div>
    );
  }

  // AndNode / OrNode
  const combinator = node.type === 'OrNode' ? 'or' : 'and';

  const updateChild = (index: number, child: AstNode) => {
    const children = [...node.children];
    children[index] = child;
    onChange({ ...node, children });
  };

  const removeChild = (index: number) => {
    const children = node.children.filter((_, i) => i !== index);
    onChange({ ...node, children });
  };

  const addCondition = () => {
    onChange({ ...node, children: [...node.children, emptyCondition()] });
  };

  const addGroup = () => {
    onChange({ ...node, children: [...node.children, emptyGroup()] });
  };

  const wrapWithNot = (index: number) => {
    const children = [...node.children];
    children[index] = { type: 'NotNode', child: children[index] };
    onChange({ ...node, children });
  };

  /** 渲染单个子节点，按 type 分发到 ConditionCard 或递归 GroupEditor */
  const renderChild = (child: AstNode, index: number) => {
    if (child.type === 'ConditionNode') {
      return (
        <ConditionCard
          node={child}
          conditionTypes={conditionTypes}
          availableMetrics={availableMetrics}
          payloadFieldNames={payloadFieldNames}
          onChange={(n) => updateChild(index, n)}
          onDelete={() => removeChild(index)}
        />
      );
    }
    return (
      <GroupEditor
        node={child as AndNode | OrNode | NotNode}
        conditionTypes={conditionTypes}
        availableMetrics={availableMetrics}
        payloadFieldNames={payloadFieldNames}
        onChange={(n) => updateChild(index, n)}
        onDelete={() => removeChild(index)}
      />
    );
  };

  return (
    <div
      style={{
        border: '1px solid #d9d9d9',
        borderRadius: 6,
        padding: '10px 12px',
        marginBottom: 4,
        background: '#fafafa',
      }}
    >
      {/* 组头 */}
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 8 }}>
        <Select
          size="small"
          style={{ width: 70 }}
          value={combinator}
          onChange={(val) => {
            if (val === 'or') {
              onChange({ type: 'OrNode', children: node.children });
            } else {
              onChange({ type: 'AndNode', children: node.children });
            }
          }}
          options={[
            { value: 'and', label: t('editor.groupEditor.and') },
            { value: 'or', label: t('editor.groupEditor.or') },
          ]}
        />
        <span style={{ fontSize: 12, color: '#999' }}>
          {combinator === 'and' ? t('editor.groupEditor.descriptionAnd') : t('editor.groupEditor.descriptionOr')}
        </span>
        <div style={{ flex: 1 }} />
        <Button size="small" icon={<PlusOutlined />} onClick={addCondition}>{t('editor.groupEditor.addCondition')}</Button>
        <Button size="small" onClick={addGroup}>{t('editor.groupEditor.addGroup')}</Button>
        {onDelete && <Button size="small" danger onClick={onDelete}>{t('editor.groupEditor.deleteGroup')}</Button>}
      </div>

      {/* 子节点列表 */}
      <div style={{ marginLeft: 12 }}>
        {node.children.map((child, index) => (
          <div key={index} style={{ display: 'flex', alignItems: 'flex-start', gap: 4 }}>
            <div style={{ flex: 1 }}>{renderChild(child, index)}</div>
            {child.type !== 'NotNode' && (
              <Button
                type="text"
                size="small"
                icon={<ExceptionOutlined />}
                title={t('editor.groupEditor.wrapNot')}
                onClick={() => wrapWithNot(index)}
              />
            )}
          </div>
        ))}
        {node.children.length === 0 && (
          <div style={{ padding: 16, textAlign: 'center', color: '#ccc', fontSize: 13 }}>
            {t('editor.groupEditor.emptyHint')}
          </div>
        )}
      </div>
    </div>
  );
}
