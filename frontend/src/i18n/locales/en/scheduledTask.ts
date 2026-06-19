import type { ScheduledTaskTranslation } from '../../types';

const scheduledTask: ScheduledTaskTranslation = {
  title: { list: 'Scheduled Tasks', detail: 'Task Detail' },
  notice: 'Scheduled tasks are defined via @TriggerTask annotation and auto-registered at startup. This page manages them (enable/disable/trigger/view history). Creating or editing task definitions is not supported here.',
  action: { trigger: 'Trigger', viewDetail: 'View Detail', enable: 'Enable', disable: 'Disable' },
  triggerSuccess: 'Task triggered',
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
