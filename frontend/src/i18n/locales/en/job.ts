import type { JobTranslation } from '../../types';

const job: JobTranslation = {
  title: { list: 'Jobs', detail: 'Job Detail' },
  notice: 'Jobs are defined via @RuleJob annotation on the backend. This page manages them (enable/disable/trigger/view history). Creating or editing job definitions is not supported here.',
  action: { trigger: 'Trigger', viewDetail: 'View Detail', enable: 'Enable', disable: 'Disable' },
  triggerSuccess: 'Job triggered',
  column: {
    name: 'Name',
    code: 'Code',
    sceneCode: 'Scene',
    eventType: 'Event Type',
    cronExpr: 'Cron Expression',
    status: 'Status',
    subjectQueryType: 'Query Type',
    actions: 'Actions',
  },
  enum: {
    execStatus: {
      RUNNING: 'Running',
      SUCCESS: 'Success',
      PARTIAL_FAIL: 'Partial Fail',
      FAILED: 'Failed',
    },
  },
  execution: {
    title: 'Execution History',
    triggerConfirm: 'Trigger job immediately: {name}. Confirm?',
    column: {
      id: 'Execution ID',
      triggerAt: 'Triggered',
      finishedAt: 'Finished',
      subjectCount: 'Subjects',
      successCount: 'Success',
      errorCount: 'Errors',
      status: 'Status',
      errorSummary: 'Error Summary',
    },
  },
};

export default job;
