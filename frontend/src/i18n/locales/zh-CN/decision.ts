import type { DecisionTranslation } from '../../types';

const decision: DecisionTranslation = {
  title: { list: 'Decision 列表' },
  action: { create: '新建 Decision', edit: '编辑 Decision' },
  column: {
    code: 'Code',
    name: '名称',
    priority: '优先级',
    status: '状态',
    description: '说明',
    createdAt: '创建时间',
    updatedAt: '更新时间',
  },
  form: {
    code: 'Code',
    codePlaceholder: '如 REJECT / REVIEW / PASS',
    codeDisabled: '创建后不可修改',
    name: '名称',
    priority: '优先级',
    priorityExtra: '数值越小优先级越高',
    description: '说明',
  },
};

export default decision;
