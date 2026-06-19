import type { ScheduledTaskTranslation } from '../../types';

const scheduledTask: ScheduledTaskTranslation = {
  title: { list: '调度任务', detail: '任务详情' },
  notice: '内置任务（如规则触发）由后端 @TriggerTask 注解定义、启动期自动落库；结论回流（OUTCOME_INGESTION）任务可在此创建。所有任务均支持启用/禁用/手动触发/查看执行记录。',
  action: { trigger: '手动触发', viewDetail: '查看详情', enable: '启用', disable: '禁用', createIngestion: '创建回灌任务', delete: '删除', deleteConfirm: '确认删除任务「{{name}}」？TRIGGER 任务下次启动会被注解重新创建，OUTCOME_INGESTION 删后不恢复。' },
  triggerSuccess: '任务已触发',
  create: {
    title: '创建回灌任务（OUTCOME_INGESTION）',
    createSuccess: '任务创建成功',
    createFailed: '创建失败',
    selectTenant: '请先选择租户',
    submit: '创建',
    field: {
      code: '任务编码', codeRequired: '请输入任务编码', codeExtra: '租户内唯一，如 fraud-ingest-daily',
      name: '任务名称', nameRequired: '请输入任务名称',
      cron: 'Cron 表达式', cronRequired: '请输入 cron 表达式', cronExtra: 'Spring 6 段 cron，如 0 0 2 * * *（每天凌晨 2 点）',
      datasource: '数据源名称', datasourceRequired: '请输入数据源名称', datasourceExtra: 'MetricDataSourceRegistry 中已注册的数据源名', datasourcePlaceholder: '请选择数据源',
      tableName: '标签表名', tableNameRequired: '请输入标签表名', tableNameExtra: '选完表名后在下方把业务列映射到 event_id / outcome_label / outcome_value / labeled_at', tableNamePlaceholder: '请选择表名',
      conditions: '附加过滤条件', conditionsExtra: '可选。每行一个条件，多个条件以 AND 连接',
      conditionFieldPlaceholder: '字段名，如 status', conditionFieldNoTable: '请先选择表名', conditionValuePlaceholder: '值，如 CONFIRMED', addCondition: '添加过滤条件',
      limitRows: '每批行数上限', limitRowsExtra: '单次拉取的最大行数（1–10000，默认 1000）',
      sqlPreview: '预览 SQL',
    },
    mapping: {
      eventId: 'event_id 列', eventIdExtra: '映射到业务事件唯一标识（关联 evaluation_session.event_id）',
      outcomeLabel: 'outcome_label', labelModeFixed: '固定值', labelModeColumn: '从列选', labelFixedPlaceholder: '如 FRAUD',
      outcomeValue: 'outcome_value 列', outcomeValueExtra: '可选，如真实损失金额，不映射则存 NULL',
      labeledAt: 'labeled_at 列', labeledAtExtra: '标注时间列，同时作为增量水位（:watermark）',
      selectColumn: '选择列', noMapping: '不映射（NULL）',
    },
  },
  column: {
    name: '名称',
    code: '编码',
    taskType: '任务类型',
    cronExpr: '定时表达式',
    status: '状态',
    actions: '操作',
  },
  type: {
    trigger: '规则触发',
    ingestion: '结论回流',
  },
  enum: {
    status: {
      ACTIVE: '已启用',
      DISABLED: '已禁用',
    },
    execStatus: {
      RUNNING: '运行中',
      SUCCESS: '成功',
      PARTIAL_FAIL: '部分失败',
      FAILED: '失败',
    },
  },
  detail: {
    basicInfo: '基本信息',
    config: '任务配置',
  },
  execution: {
    title: '执行历史',
    triggerConfirm: '将立即执行一次任务：{{name}}。确认？',
    column: {
      id: '执行编号',
      triggerAt: '触发时间',
      finishedAt: '完成时间',
      processedCount: '处理数',
      successCount: '成功数',
      errorCount: '失败数',
      status: '状态',
      errorSummary: '错误摘要',
    },
  },
};

export default scheduledTask;
