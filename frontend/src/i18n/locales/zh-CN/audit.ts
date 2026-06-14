import type { AuditTranslation } from '../../types';

const audit: AuditTranslation = {
  title: { list: '审计日志' },
  column: {
    actor: '操作人',
    actorType: '类型',
    action: '操作',
    targetType: '对象类型',
    targetId: '对象 ID',
    operatedAt: '操作时间',
  },
  filter: {
    targetType: '对象类型',
    targetId: '对象 ID',
    actor: '操作人',
    action: '操作类型',
    from: '开始时间',
    to: '结束时间',
  },
  enum: {
    action: {
      CREATE: '创建',
      UPDATE: '更新',
      PUBLISH: '发布',
      PUBLISH_FAILED: '发布失败',
      ENABLE: '启用',
      DISABLE: '禁用',
      DELETE: '删除',
      IMPORT: '导入',
    },
    targetType: { RULE: '规则', SCENE: '场景', METRIC: '指标', DECISION: '决策', JOB: 'Job' },
  },
  diff: { before: '变更前', after: '变更后', expand: '展开详情', noDiff: '无差异', renderError: '差异渲染失败', calcError: '差异计算失败', noSnapshot: '无快照数据' },
};

export default audit;
