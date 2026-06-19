import type { ScheduledTaskTranslation } from '../../types';

const scheduledTask: ScheduledTaskTranslation = {
  title: { list: '调度任务', detail: '任务详情' },
  notice: '调度任务由后端 @TriggerTask 注解定义、启动期自动落库，此处仅管理（启用/禁用/手动触发/查看执行记录），不支持创建或编辑任务定义。',
  action: { trigger: '手动触发', viewDetail: '查看详情', enable: '启用', disable: '禁用' },
  triggerSuccess: '任务已触发',
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
