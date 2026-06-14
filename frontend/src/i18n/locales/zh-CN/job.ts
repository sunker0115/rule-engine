import type { JobTranslation } from '../../types';

const job: JobTranslation = {
  title: { list: '任务管理', detail: '任务详情' },
  notice: '任务由后端 @RuleJob 注解定义，此处仅管理（启用/禁用/手动触发/查看执行记录），不支持创建或编辑任务定义。',
  action: { trigger: '手动触发', viewDetail: '查看详情', enable: '启用', disable: '禁用' },
  triggerSuccess: '任务已触发',
  column: {
    name: '名称',
    code: '编码',
    sceneCode: '场景',
    eventType: '事件类型',
    cronExpr: '定时表达式',
    status: '状态',
    subjectQueryType: '查询方式',
    actions: '操作',
  },
  enum: {
    execStatus: {
      RUNNING: '运行中',
      SUCCESS: '成功',
      PARTIAL_FAIL: '部分失败',
      FAILED: '失败',
    },
  },
  execution: {
    title: '执行历史',
    triggerConfirm: '将立即执行一次任务：{name}。确认？',
    column: {
      id: '执行编号',
      triggerAt: '触发时间',
      finishedAt: '完成时间',
      subjectCount: '主体数',
      successCount: '成功数',
      errorCount: '失败数',
      status: '状态',
      errorSummary: '错误摘要',
    },
  },
};

export default job;
