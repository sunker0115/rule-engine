import type { MetricTranslation } from '../../types';

const metric: MetricTranslation = {
  title: { list: '指标列表', detail: '指标详情' },
  action: { create: '注册指标', queryImpact: '查询引用的规则' },
  column: {
    metricCode: '指标编码',
    name: '名称',
    sourceType: '取数方式',
    dataType: '数据类型',
    version: '版本',
    allowProvided: '允许外部注入',
    cacheTtl: '缓存(秒)',
    status: '状态',
  },
  enum: {
    sourceType: {
      ATTRIBUTE: '属性表',
      SQL_AGGREGATE: 'SQL 聚合',
      EXTERNAL_HTTP: '外部 HTTP',
      STREAM: '流处理',
    },
    dataType: {
      LONG: '整数',
      DOUBLE: '浮点',
      STRING: '字符串',
      BOOLEAN: '布尔',
      LIST: '列表',
      DATE: '日期',
      DATETIME: '日期时间',
    },
  },
  form: {
    code: '指标编码',
    codePlaceholder: '如 user.trade.sum.7d',
    name: '名称',
    sourceType: '取数方式',
    dataType: '数据类型',
    cacheTtl: '缓存 (秒)',
    allowProvided: '允许外部注入',
    params: {
      table: '表名',
      column: '列名',
      datasource: '数据源',
      sql: 'SQL',
      endpoint: '接口地址',
      path: '路径',
      pathPlaceholder: '/api/v1/risk/{payload.userId}',
      jsonPath: 'JSON 路径',
      jsonPathPlaceholder: '$.data.riskScore',
      topic: '主题',
      keyExpr: 'Key 表达式',
    },
    streamDisabled: '流处理类型 v2 接入，当前不可用',
    breakingChangeTitle: '破坏性变更',
    breakingChangeContent: 'sourceType 或 dataType 变更将产生新版本，已有规则仍绑定旧版本。确认继续？',
  },
  searchPlaceholder: '搜索名称或编码',
  detail: { basicInfo: '基本信息', version: '版本', notFound: '指标不存在' },
  impact: {
    column: { ruleCode: '规则编码', ruleName: '规则名称', sceneCode: '场景', status: '状态' },
  },
};

export default metric;
