import type { MetricTranslation } from '../../types';

const metric: MetricTranslation = {
  title: { list: 'Metrics', detail: 'Metric Detail' },
  action: { create: 'Register Metric', queryImpact: 'Query Impact' },
  column: {
    metricCode: 'Metric Code',
    name: 'Name',
    sourceType: 'Source',
    dataType: 'Data Type',
    version: 'Version',
    allowProvided: 'Provided',
    cacheTtl: 'Cache (s)',
    status: 'Status',
  },
  enum: {
    sourceType: {
      ATTRIBUTE: 'Attribute',
      SQL_AGGREGATE: 'SQL Aggregate',
      EXTERNAL_HTTP: 'External HTTP',
      STREAM: 'Stream',
    },
    dataType: {
      LONG: 'Long',
      DOUBLE: 'Double',
      STRING: 'String',
      BOOLEAN: 'Boolean',
      LIST: 'List',
      DATE: 'Date',
      DATETIME: 'Datetime',
    },
  },
  form: {
    code: 'Metric Code',
    codePlaceholder: 'e.g. user.trade.sum.7d',
    name: 'Name',
    sourceType: 'Source Type',
    dataType: 'Data Type',
    cacheTtl: 'Cache TTL (s)',
    allowProvided: 'Allow Provided',
    params: {
      table: 'Table',
      column: 'Column',
      datasource: 'Datasource',
      sql: 'SQL',
      endpoint: 'Endpoint',
      path: 'Path',
      pathPlaceholder: '/api/v1/risk/{payload.userId}',
      jsonPath: 'JSON Path',
      jsonPathPlaceholder: '$.data.riskScore',
      topic: 'Topic',
      keyExpr: 'Key Expression',
    },
    streamDisabled: 'Stream type v2 is currently unavailable',
    breakingChangeTitle: 'Breaking Change',
    breakingChangeContent: 'Changing sourceType or dataType creates a new version. Existing rules remain bound to the old version. Continue?',
  },
  searchPlaceholder: 'Search by name or code',
  detail: { basicInfo: 'Basic Info', version: 'Version', notFound: 'Metric not found' },
  impact: {
    column: { ruleCode: 'Rule Code', ruleName: 'Rule Name', sceneCode: 'Scene', status: 'Status' },
  },
};

export default metric;
