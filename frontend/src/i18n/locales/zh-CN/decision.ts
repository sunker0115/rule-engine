import type { DecisionTranslation } from '../../types';

const decision: DecisionTranslation = {
  title: { list: '决策列表' },
  action: { create: '新建决策', edit: '编辑决策', disable: '停用', enable: '启用' },
  column: {
    code: '编码',
    name: '名称',
    priority: '优先级',
    status: '状态',
    description: '说明',
    createdAt: '创建时间',
    updatedAt: '更新时间',
    usage: '被引用',
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
  detail: { basicInfo: '基本信息', sources: '被引用规则', notFound: '决策不存在' },
};

export default decision;
