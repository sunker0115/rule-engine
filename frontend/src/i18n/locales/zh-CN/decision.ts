import type { DecisionTranslation } from '../../types';

const decision: DecisionTranslation = {
  title: { list: '决策列表' },
  action: { create: '新建决策', edit: '编辑决策' },
  column: {
    code: '编码',
    name: '名称',
    priority: '优先级',
    status: '状态',
    description: '说明',
    createdAt: '创建时间',
    updatedAt: '更新时间',
  },
  form: {
    code: '编码',
    codePlaceholder: '如 REJECT / REVIEW / PASS',
    codeDisabled: '创建后不可修改',
    name: '名称',
    priority: '优先级',
    priorityExtra: '数值越小优先级越高',
    description: '说明',
  },
};

export default decision;
