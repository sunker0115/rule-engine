export interface JobItem {
  id: number;
  name: string;
  code: string;
  sceneCode: string;
  eventType: string;
  cronExpression: string;
  status: 'ACTIVE' | 'DISABLED';
  subjectQuery: { type: string; ref: string };
}

export type JobExecStatus = 'RUNNING' | 'SUCCESS' | 'PARTIAL_FAIL' | 'FAILED';

export interface JobExecutionItem {
  id: number;
  jobDefinitionId: number;
  triggerAt: string;
  finishedAt?: string;
  subjectCount: number;
  successCount: number;
  errorCount: number;
  status: JobExecStatus;
  errorSummary?: string;
}
