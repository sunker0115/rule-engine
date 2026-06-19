import type { ScheduledTaskTranslation } from '../../types';

const scheduledTask: ScheduledTaskTranslation = {
  title: { list: 'Scheduled Tasks', detail: 'Task Detail' },
  notice: 'Built-in tasks (e.g. rule triggers) are defined via @TriggerTask annotation and auto-registered at startup; outcome-ingestion (OUTCOME_INGESTION) tasks can be created here. All tasks support enable/disable/trigger/view history.',
  action: { trigger: 'Trigger', viewDetail: 'View Detail', enable: 'Enable', disable: 'Disable', createIngestion: 'Create Ingestion Task', delete: 'Delete', deleteConfirm: 'Delete task "{{name}}"? TRIGGER tasks are re-seeded by the annotation on next startup; OUTCOME_INGESTION tasks are not restored.' },
  triggerSuccess: 'Task triggered',
  create: {
    title: 'Create Ingestion Task (OUTCOME_INGESTION)',
    createSuccess: 'Task created',
    createFailed: 'Create failed',
    selectTenant: 'Please select a tenant first',
    submit: 'Create',
    field: {
      code: 'Task Code', codeRequired: 'Please enter task code', codeExtra: 'Unique within tenant, e.g. fraud-ingest-daily',
      name: 'Task Name', nameRequired: 'Please enter task name',
      cron: 'Cron Expression', cronRequired: 'Please enter cron expression', cronExtra: 'Spring 6-field cron, e.g. 0 0 2 * * * (2 AM daily)',
      datasource: 'Datasource Name', datasourceRequired: 'Please enter datasource name', datasourceExtra: 'A datasource registered in MetricDataSourceRegistry', datasourcePlaceholder: 'Select a datasource',
      tableName: 'Label Table', tableNameRequired: 'Please enter label table name', tableNameExtra: 'After picking the table, map its business columns to event_id / outcome_label / outcome_value / labeled_at below', tableNamePlaceholder: 'Select a table',
      conditions: 'Additional Filters', conditionsExtra: 'Optional. Multiple conditions are joined with AND.',
      conditionFieldPlaceholder: 'Field name, e.g. status', conditionFieldNoTable: 'Select a table first', conditionValuePlaceholder: 'Value, e.g. CONFIRMED', addCondition: 'Add filter condition',
      limitRows: 'Max Rows Per Batch', limitRowsExtra: 'Max rows fetched per run (1-10000, default 1000)',
      sqlPreview: 'SQL Preview',
    },
    mapping: {
      eventId: 'event_id column', eventIdExtra: 'Maps to the business event unique id (joins evaluation_session.event_id)',
      outcomeLabel: 'outcome_label', labelModeFixed: 'Fixed value', labelModeColumn: 'From column', labelFixedPlaceholder: 'e.g. FRAUD',
      outcomeValue: 'outcome_value column', outcomeValueExtra: 'Optional, e.g. actual loss amount; stored as NULL if unmapped',
      labeledAt: 'labeled_at column', labeledAtExtra: 'Labeling timestamp column, also used as the incremental watermark (:watermark)',
      selectColumn: 'Select a column', noMapping: 'No mapping (NULL)',
    },
  },
  column: {
    name: 'Name',
    code: 'Code',
    taskType: 'Task Type',
    cronExpr: 'Cron Expression',
    status: 'Status',
    actions: 'Actions',
  },
  type: {
    trigger: 'Rule Trigger',
    ingestion: 'Outcome Ingestion',
  },
  enum: {
    status: {
      ACTIVE: 'Active',
      DISABLED: 'Disabled',
    },
    execStatus: {
      RUNNING: 'Running',
      SUCCESS: 'Success',
      PARTIAL_FAIL: 'Partial Fail',
      FAILED: 'Failed',
    },
  },
  detail: {
    basicInfo: 'Basic Info',
    config: 'Task Config',
  },
  execution: {
    title: 'Execution History',
    triggerConfirm: 'Trigger task immediately: {{name}}. Confirm?',
    column: {
      id: 'Execution ID',
      triggerAt: 'Triggered',
      finishedAt: 'Finished',
      processedCount: 'Processed',
      successCount: 'Success',
      errorCount: 'Errors',
      status: 'Status',
      errorSummary: 'Error Summary',
    },
  },
};

export default scheduledTask;
