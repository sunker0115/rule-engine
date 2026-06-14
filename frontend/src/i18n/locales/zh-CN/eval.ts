import type { EvalTranslation } from '../../types';

const evalLoc: EvalTranslation = {
  title: { dryRun: '试算', sessionList: '评估会话', sessionDetail: '会话详情' },
  dryRun: {
    execute: '执行试算',
    targetVersion: '目标版本',
    eventType: '事件类型',
    subjectId: '主体 ID',
    payload: '事件数据 (payload)',
    result: {
      hit: '命中',
      miss: '未命中',
      blocked: '被拦截',
      hitDecisions: '命中决策',
      finalDecision: '最终决策',
      nodeTrace: '节点 Trace',
    },
  },
  session: {
    column: {
      sessionId: 'Session ID',
      eventId: 'Event ID',
      sceneCode: 'Scene',
      eventType: '事件类型',
      subjectId: '主体 ID',
      status: '状态',
      finalDecision: '最终决策',
      candidateRuleCount: '候选规则',
      hitRuleCount: '命中规则',
      source: '来源',
      mode: '模式',
      evalDuration: '耗时(ms)',
      occurredAt: '业务时间',
    },
    filter: {
      sceneCode: 'Scene',
      subjectId: '主体 ID',
      eventId: 'Event ID',
      status: '状态',
      source: '来源',
      from: '开始时间',
      to: '结束时间',
    },
    detail: {
      basicInfo: '基本信息',
      traceTree: 'Trace 树',
      hitRules: '命中规则',
      contextSnapshot: '上下文快照',
    },
  },
  trace: {
    expandAll: '全部展开',
    collapseAll: '全部折叠',
    copyJson: '复制 JSON',
    nodeSatisfied: '满足',
    nodeUnsatisfied: '不满足',
    nodeSkipped: '短路跳过',
    nodeError: '错误',
    preGateBlocked: 'Pre-Gate 拦截',
  },
};

export default evalLoc;
