import i18n from '@/i18n';

/** 通用状态 */
export const STATUS_OPTIONS = [
  { value: 'ACTIVE',   label: i18n.t('common.enum.status.ACTIVE'),   color: 'green'  },
  { value: 'DISABLED', label: i18n.t('common.enum.status.DISABLED'), color: 'red'    },
] as const;

/** Scene 使用模式 */
export const DOMINANT_MODE_OPTIONS = [
  { value: 'PUSH',   label: i18n.t('scene.enum.dominantMode.PUSH')   },
  { value: 'PULL',   label: i18n.t('scene.enum.dominantMode.PULL')   },
  { value: 'HYBRID', label: i18n.t('scene.enum.dominantMode.HYBRID') },
] as const;

/** 规则状态 */
export const RULE_STATUS_OPTIONS = [
  { value: 'DRAFT',     label: i18n.t('rule.enum.status.DRAFT'),     color: 'blue'   },
  { value: 'PUBLISHED', label: i18n.t('rule.enum.status.PUBLISHED'), color: 'green'  },
  { value: 'DISABLED',  label: i18n.t('rule.enum.status.DISABLED'),  color: 'red'    },
] as const;

/** 版本状态 */
export const VERSION_STATUS_OPTIONS = [
  { value: 'DRAFT',      label: i18n.t('rule.enum.versionStatus.DRAFT'),      color: 'blue'    },
  { value: 'ACTIVE',     label: i18n.t('rule.enum.versionStatus.ACTIVE'),     color: 'green'   },
  { value: 'SUPERSEDED', label: i18n.t('rule.enum.versionStatus.SUPERSEDED'), color: 'default' },
] as const;

/** Metric 取数方式 */
export const SOURCE_TYPE_OPTIONS = [
  { value: 'ATTRIBUTE',     label: i18n.t('metric.enum.sourceType.ATTRIBUTE')     },
  { value: 'SQL_AGGREGATE', label: i18n.t('metric.enum.sourceType.SQL_AGGREGATE') },
  { value: 'EXTERNAL_HTTP', label: i18n.t('metric.enum.sourceType.EXTERNAL_HTTP') },
  { value: 'STREAM',        label: i18n.t('metric.enum.sourceType.STREAM')        },
] as const;

/** Metric 数据类型 */
export const DATA_TYPE_OPTIONS = [
  { value: 'LONG',     label: i18n.t('metric.enum.dataType.LONG')     },
  { value: 'DOUBLE',   label: i18n.t('metric.enum.dataType.DOUBLE')   },
  { value: 'STRING',   label: i18n.t('metric.enum.dataType.STRING')   },
  { value: 'BOOLEAN',  label: i18n.t('metric.enum.dataType.BOOLEAN')  },
  { value: 'LIST',     label: i18n.t('metric.enum.dataType.LIST')     },
  { value: 'DATE',     label: i18n.t('metric.enum.dataType.DATE')     },
  { value: 'DATETIME', label: i18n.t('metric.enum.dataType.DATETIME') },
] as const;

/** 规则 kind */
export const RULE_KIND_OPTIONS = [
  { value: 'AST_BOOLEAN',        label: i18n.t('rule.enum.kind.AST_BOOLEAN')        },
  { value: 'SCORECARD',          label: i18n.t('rule.enum.kind.SCORECARD')          },
  { value: 'DECISION_TREE',      label: i18n.t('rule.enum.kind.DECISION_TREE')      },
  { value: 'DECISION_TABLE',     label: i18n.t('rule.enum.kind.DECISION_TABLE')     },
  { value: 'EXPRESSION_SCRIPT',  label: i18n.t('rule.enum.kind.EXPRESSION_SCRIPT')  },
] as const;

/** 评估会话状态 */
export const SESSION_STATUS_OPTIONS = [
  { value: 'HIT',     label: i18n.t('eval.enum.sessionStatus.HIT'),     color: 'green'   },
  { value: 'MISS',    label: i18n.t('eval.enum.sessionStatus.MISS'),    color: 'default' },
  { value: 'BLOCKED', label: i18n.t('eval.enum.sessionStatus.BLOCKED'), color: 'orange'  },
  { value: 'ERROR',   label: i18n.t('eval.enum.sessionStatus.ERROR'),   color: 'red'     },
  { value: 'PENDING', label: i18n.t('eval.enum.sessionStatus.PENDING'), color: 'blue'    },
  { value: 'FAILED',  label: i18n.t('eval.enum.sessionStatus.FAILED'),  color: '#8b0000' },
] as const;

/** 事件来源渠道 */
export const EVENT_SOURCE_OPTIONS = [
  { value: 'HTTP',   label: 'HTTP'   },
  { value: 'MQ',     label: 'MQ'     },
  { value: 'JOB',    label: 'Job'    },
  { value: 'SDK',    label: 'SDK'    },
  { value: 'REPLAY', label: 'Replay' },
] as const;

/** 审计操作类型 */
export const AUDIT_ACTION_OPTIONS = [
  { value: 'CREATE',         label: i18n.t('audit.enum.action.CREATE'),         color: 'blue'    },
  { value: 'UPDATE',         label: i18n.t('audit.enum.action.UPDATE'),         color: 'blue'    },
  { value: 'PUBLISH',        label: i18n.t('audit.enum.action.PUBLISH'),        color: 'green'   },
  { value: 'PUBLISH_FAILED', label: i18n.t('audit.enum.action.PUBLISH_FAILED'), color: 'red'     },
  { value: 'ENABLE',         label: i18n.t('audit.enum.action.ENABLE'),         color: 'green'   },
  { value: 'DISABLE',        label: i18n.t('audit.enum.action.DISABLE'),        color: 'orange'  },
  { value: 'DELETE',         label: i18n.t('audit.enum.action.DELETE'),         color: 'red'     },
  { value: 'IMPORT',         label: i18n.t('audit.enum.action.IMPORT'),         color: 'purple'  },
] as const;

/** 审计目标类型 */
export const AUDIT_TARGET_TYPE_OPTIONS = [
  { value: 'RULE',     label: i18n.t('audit.enum.targetType.RULE')     },
  { value: 'SCENE',    label: i18n.t('audit.enum.targetType.SCENE')    },
  { value: 'METRIC',   label: i18n.t('audit.enum.targetType.METRIC')   },
  { value: 'DECISION', label: i18n.t('audit.enum.targetType.DECISION') },
  { value: 'JOB',      label: i18n.t('audit.enum.targetType.JOB')      },
] as const;

/** Job 执行状态 */
export const JOB_EXEC_STATUS_OPTIONS = [
  { value: 'RUNNING',      label: i18n.t('job.enum.execStatus.RUNNING'),      color: 'blue'   },
  { value: 'SUCCESS',      label: i18n.t('job.enum.execStatus.SUCCESS'),      color: 'green'  },
  { value: 'PARTIAL_FAIL', label: i18n.t('job.enum.execStatus.PARTIAL_FAIL'), color: 'orange' },
  { value: 'FAILED',       label: i18n.t('job.enum.execStatus.FAILED'),       color: 'red'    },
] as const;

/** 评估模式 */
export const EVAL_MODE_OPTIONS = [
  { value: 'PUSH', label: 'PUSH' },
  { value: 'PULL', label: 'PULL' },
] as const;

/** Actor 类型 */
export const ACTOR_TYPE_OPTIONS = [
  { value: 'USER',   label: i18n.t('common.enum.actorType.USER')   },
  { value: 'SYSTEM', label: i18n.t('common.enum.actorType.SYSTEM') },
  { value: 'JOB',    label: i18n.t('common.enum.actorType.JOB')    },
] as const;

/** 工具函数：按 value 取 label */
export function labelOf<T extends string>(
  options: ReadonlyArray<{ readonly value: T; readonly label: string }>,
  value: T,
): string {
  return options.find((o) => o.value === value)?.label ?? value;
}

/** 工具函数：按 value 取 color（用于 Tag） */
export function colorOf<T extends string>(
  options: ReadonlyArray<{ readonly value: T; readonly label: string; readonly color?: string }>,
  value: T,
): string | undefined {
  return options.find((o) => o.value === value)?.color;
}
