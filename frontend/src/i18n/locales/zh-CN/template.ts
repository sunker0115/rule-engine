import type { TemplateTranslation } from '../../types';

const template: TemplateTranslation = {
  title: { list: '模板管理', editor: '编辑模板', instantiate: '实例化模板' },
  action: {
    create: '创建模板', edit: '编辑', publish: '发布', publishConfirm: '确认发布此模板？',
    disable: '禁用', disableConfirm: '确认禁用此模板？',
    instantiate: '实例化',
    remove: '移除',
    back: '返回', save: '保存', saveSuccess: '保存成功',
  },
  column: {
    name: '名称', code: '编码', kind: '类型', version: '版本',
    slots: 'Slots', status: '状态',
    createdAt: '创建时间', actions: '操作',
  },
  enum: {
    version: 'v',
    status: { DRAFT: '草稿', PUBLISHED: '已发布', DISABLED: '已禁用' },
    dataType: {
      LONG: '长整数', DOUBLE: '浮点数', DECIMAL: '小数', STRING: '字符串',
      BOOLEAN: '布尔', DATE: '日期', DATETIME: '日期时间', LIST: '列表',
    },
  },
  form: {
    name: '名称', code: '编码', kind: '模板类型',
    description: '说明',
    basicInfo: '基本信息',
    bodySkeleton: 'Body 骨架（含默认值）',
    slots: 'Slot 定义',
    slotKey: 'Key',
    slotLabel: '标签', slotLabelPlaceholder: '运营可见名称',
    slotDataType: '数据类型',
    slotRequired: '必填',
    referenceScene: '参照场景',
    referenceScenePlaceholder: '选择场景以加载指标/字段/决策元数据',
    referenceSceneHint: '仅用于取指标/字段/决策元数据辅助编辑，不写入模板',
    parameterize: '参数化位置',
    parameterizePlaceholder: '选择规则体中要参数化的位置',
    parameterizeScriptHint: '脚本类型经下方参数表的「参数化」开关声明 slot',
    slotEnum: '枚举值', slotMin: '最小值', slotMax: '最大值',
  },
  instantiate: {
    selectTenant: '目标租户',
    selectScene: '目标场景',
    ruleCode: '规则编码', ruleName: '规则名称',
    triggerEventTypes: '事件类型',
    fillSlots: '填写参数',
    submit: '立即实例化',
    success: '实例化成功！',
    goToRule: '前往编辑',
  },
};

export default template;
