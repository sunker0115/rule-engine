import type { SceneTranslation } from '../../types';

const scene: SceneTranslation = {
  title: { list: 'Scene 列表', detail: 'Scene 详情' },
  action: { create: '新建 Scene' },
  column: {
    sceneCode: 'Scene Code',
    name: '名称',
    dominantMode: '模式',
    subjectType: '主体类型',
    status: '状态',
    actions: '操作',
  },
  enum: {
    dominantMode: { PUSH: 'PUSH (异步评估)', PULL: 'PULL (同步评估)', HYBRID: 'HYBRID (混合)' },
  },
  form: {
    code: 'Scene Code',
    codePlaceholder: '如 risk.transfer',
    name: '名称',
    dominantMode: '使用模式',
    subjectType: '主体类型',
    description: '说明',
    payloadSchema: 'payloadSchema (JSON)',
    payloadSchemaExtra: '定义 payload 允许的字段与类型',
    eventTypes: '事件类型白名单',
    eventTypesPlaceholder: '输入后回车添加',
    defaultParams: 'defaultParams (JSON)',
    defaultParamsExtra: 'Scene 级缺省参数',
    decisionStrategy: '决策策略',
    status: '状态',
  },
  detail: {
    basicInfo: '基本信息',
    inputManifest: '输入清单',
    ruleList: '规则列表',
    notFound: 'Scene 不存在',
  },
  inputManifest: {
    info: '调用方对该场景发评估请求时，payload 需包含以下字段',
    filterEventType: '按事件类型筛选',
    filterAll: '全部事件类型',
    column: { name: '字段名', dataType: '类型', required: '必填' },
    required: '必填',
    optional: '可选',
    exampleTitle: '请求体 payload 示例',
  },
};

export default scene;
