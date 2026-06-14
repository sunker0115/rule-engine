import type { CommonTranslation } from '../../types';

const common: CommonTranslation = {
  app: { title: '规则引擎运营平台' },
  header: { actorLabel: '操作人' },
  tenant: { placeholder: '选择租户' },
  button: {
    back: '返回',
    save: '保存',
    cancel: '取消',
    edit: '编辑',
    delete: '删除',
    confirm: '确认',
    submit: '提交',
    refresh: '刷新',
    copy: '复制 JSON',
  },
  label: {
    id: 'ID',
    code: 'Code',
    name: '名称',
    status: '状态',
    description: '说明',
    actions: '操作',
    createdAt: '创建时间',
    updatedAt: '更新时间',
    none: '-',
    yes: '是',
    no: '否',
    all: '全部',
    tenant: '租户',
  },
  enum: {
    status: { ACTIVE: '启用', DISABLED: '禁用' },
    actorType: { USER: '用户', SYSTEM: '系统', JOB: 'Job' },
  },
  message: {
    createSuccess: '创建成功',
    updateSuccess: '更新成功',
    deleteSuccess: '删除成功',
    saveSuccess: '保存成功',
    loadError: '加载失败',
    confirmDelete: '确认删除？此操作不可撤销',
  },
  validation: {
    required: '请输入',
    jsonFormat: 'JSON 格式错误',
  },
};

export default common;
