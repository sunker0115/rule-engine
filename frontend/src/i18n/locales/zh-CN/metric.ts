import type { MetricTranslation } from '../../types';

const metric: MetricTranslation = {
  title: { list: 'Metric 列表', detail: 'Metric 详情' },
  action: { create: '注册 Metric', queryImpact: '查询引用的规则' },
  column: {
    metricCode: 'Metric Code',
    name: '名称',
    sourceType: '取数方式',
    dataType: '数据类型',
    version: '版本',
    allowProvided: 'allowProvided',
    cacheTtl: '缓存 TTL(s)',
    status: '状态',
  },
  enum: {
    sourceType: {
      ATTRIBUTE: '属性表 (ATTRIBUTE)',
      SQL_AGGREGATE: 'SQL 聚合 (SQL_AGGREGATE)',
      EXTERNAL_HTTP: '外部 HTTP (EXTERNAL_HTTP)',
      STREAM: '流处理 (STREAM)',
    },
    dataType: {
      LONG: 'LONG (整数)',
      DOUBLE: 'DOUBLE (浮点)',
      STRING: 'STRING (字符串)',
      BOOLEAN: 'BOOLEAN (布尔)',
      LIST: 'LIST (列表)',
      DATE: 'DATE (日期)',
      DATETIME: 'DATETIME (日期时间)',
    },
  },
  form: {
    code: 'Metric Code',
    codePlaceholder: '如 user.trade.sum.7d',
    name: '名称',
    sourceType: '取数方式',
    dataType: '数据类型',
    cacheTtl: '缓存 TTL (秒)',
    allowProvided: '允许外部注入',
    params: {
      table: '表名',
      column: '列名',
      datasource: '数据源',
      sql: 'SQL',
      endpoint: 'Endpoint',
      path: '路径',
      pathPlaceholder: '/api/v1/risk/{payload.userId}',
      jsonPath: 'JSON Path',
      jsonPathPlaceholder: '$.data.riskScore',
      topic: 'Topic',
      keyExpr: 'Key 表达式',
    },
    streamDisabled: 'STREAM 类型 v2 接入，当前不可用',
    breakingChangeTitle: '破坏性变更',
    breakingChangeContent: 'sourceType 或 dataType 变更将产生新版本，已有规则仍绑定旧版本。确认继续？',
  },
  impact: {
    column: { ruleCode: '规则 Code', ruleName: '规则名称', sceneCode: 'Scene', status: '状态' },
  },
};

export default metric;
