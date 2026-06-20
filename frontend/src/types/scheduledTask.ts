/** 调度任务（对齐后端 ScheduledTaskVO）。taskType/status 为开放字符串，前端以 label map + 兜底渲染。 */
export interface ScheduledTaskItem {
  id: number;
  tenantId: number;
  code: string;
  name: string;
  taskType: string;
  cron: string;
  /** 配置为去中心化 JSON 对象，前端通用渲染，不绑定具体形状 */
  config: unknown;
  status: string;
  createdAt: string;
  updatedAt: string;
}

/** 调度任务执行记录（对齐后端 ScheduledTaskExecutionVO）。 */
export interface ScheduledTaskExecutionItem {
  id: number;
  scheduledTaskId: number;
  status: string;
  processedCount: number;
  successCount: number;
  errorCount: number;
  errorSummary?: string;
  triggerAt: string;
  finishedAt?: string;
}
