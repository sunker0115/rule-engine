import type { TFunction } from 'i18next';

type Option<V extends string> = { readonly value: V; readonly label: string; readonly color?: string };

/** 通用状态 */
export function getStatusOptions(t: TFunction): Option<'ACTIVE' | 'DISABLED'>[] {
  return [
    { value: 'ACTIVE',   label: t('enum.status.ACTIVE'),   color: 'green' },
    { value: 'DISABLED', label: t('enum.status.DISABLED'), color: 'red'   },
  ];
}

/** Scene 使用模式 */
export function getDominantModeOptions(t: TFunction) {
  return [
    { value: 'PUSH',   label: t('enum.dominantMode.PUSH')   },
    { value: 'PULL',   label: t('enum.dominantMode.PULL')   },
    { value: 'HYBRID', label: t('enum.dominantMode.HYBRID') },
  ] as const;
}

/** 规则状态 */
export function getRuleStatusOptions(t: TFunction) {
  return [
    { value: 'DRAFT',     label: t('enum.status.DRAFT'),     color: 'blue'   },
    { value: 'PUBLISHED', label: t('enum.status.PUBLISHED'), color: 'green'  },
    { value: 'DISABLED',  label: t('enum.status.DISABLED'),  color: 'red'    },
  ] as const;
}

/** 版本状态 */
export function getVersionStatusOptions(t: TFunction) {
  return [
    { value: 'DRAFT',      label: t('enum.versionStatus.DRAFT'),      color: 'blue'    },
    { value: 'ACTIVE',     label: t('enum.versionStatus.ACTIVE'),     color: 'green'   },
    { value: 'SUPERSEDED', label: t('enum.versionStatus.SUPERSEDED'), color: 'default' },
  ] as const;
}

/** Metric 取数方式 */
export function getSourceTypeOptions(t: TFunction) {
  return [
    { value: 'ATTRIBUTE',     label: t('enum.sourceType.ATTRIBUTE')     },
    { value: 'SQL_AGGREGATE', label: t('enum.sourceType.SQL_AGGREGATE') },
    { value: 'EXTERNAL_HTTP', label: t('enum.sourceType.EXTERNAL_HTTP') },
    { value: 'STREAM',        label: t('enum.sourceType.STREAM')        },
  ] as const;
}

/** Metric 数据类型 */
export function getDataTypeOptions(t: TFunction) {
  return [
    { value: 'LONG',     label: t('enum.dataType.LONG')     },
    { value: 'DOUBLE',   label: t('enum.dataType.DOUBLE')   },
    { value: 'STRING',   label: t('enum.dataType.STRING')   },
    { value: 'BOOLEAN',  label: t('enum.dataType.BOOLEAN')  },
    { value: 'LIST',     label: t('enum.dataType.LIST')     },
    { value: 'DATE',     label: t('enum.dataType.DATE')     },
    { value: 'DATETIME', label: t('enum.dataType.DATETIME') },
  ] as const;
}

/** 规则 kind */
export function getRuleKindOptions(t: TFunction) {
  return [
    { value: 'AST_BOOLEAN',        label: t('enum.kind.AST_BOOLEAN')        },
    { value: 'SCORECARD',          label: t('enum.kind.SCORECARD')          },
    { value: 'DECISION_TREE',      label: t('enum.kind.DECISION_TREE')      },
    { value: 'DECISION_TABLE',     label: t('enum.kind.DECISION_TABLE')     },
    { value: 'EXPRESSION_SCRIPT',  label: t('enum.kind.EXPRESSION_SCRIPT')  },
  ] as const;
}

/** 评估会话状态 */
export function getSessionStatusOptions(t: TFunction) {
  return [
    { value: 'HIT',     label: t('enum.sessionStatus.HIT'),     color: 'green'   },
    { value: 'MISS',    label: t('enum.sessionStatus.MISS'),    color: 'default' },
    { value: 'BLOCKED', label: t('enum.sessionStatus.BLOCKED'), color: 'orange'  },
    { value: 'ERROR',   label: t('enum.sessionStatus.ERROR'),   color: 'red'     },
    { value: 'PENDING', label: t('enum.sessionStatus.PENDING'), color: 'blue'    },
    { value: 'FAILED',  label: t('enum.sessionStatus.FAILED'),  color: '#8b0000' },
  ] as const;
}

/** 事件来源渠道 */
export function getEventSourceOptions(t: TFunction) {
  return [
    { value: 'HTTP' as const,   label: t('enum.source.HTTP')   },
    { value: 'MQ' as const,     label: t('enum.source.MQ')     },
    { value: 'JOB' as const,    label: t('enum.source.JOB')    },
    { value: 'SDK' as const,    label: t('enum.source.SDK')    },
    { value: 'REPLAY' as const, label: t('enum.source.REPLAY') },
  ] as const;
}

/** 审计操作类型 */
export function getAuditActionOptions(t: TFunction) {
  return [
    { value: 'CREATE',         label: t('enum.action.CREATE'),         color: 'blue'    },
    { value: 'UPDATE',         label: t('enum.action.UPDATE'),         color: 'blue'    },
    { value: 'PUBLISH',        label: t('enum.action.PUBLISH'),        color: 'green'   },
    { value: 'PUBLISH_FAILED', label: t('enum.action.PUBLISH_FAILED'), color: 'red'     },
    { value: 'ENABLE',         label: t('enum.action.ENABLE'),         color: 'green'   },
    { value: 'DISABLE',        label: t('enum.action.DISABLE'),        color: 'orange'  },
    { value: 'DELETE',         label: t('enum.action.DELETE'),         color: 'red'     },
    { value: 'IMPORT',         label: t('enum.action.IMPORT'),         color: 'purple'  },
  ] as const;
}

/** 审计目标类型 */
export function getAuditTargetTypeOptions(t: TFunction) {
  return [
    { value: 'RULE',     label: t('enum.targetType.RULE')     },
    { value: 'SCENE',    label: t('enum.targetType.SCENE')    },
    { value: 'METRIC',   label: t('enum.targetType.METRIC')   },
    { value: 'DECISION', label: t('enum.targetType.DECISION') },
    { value: 'JOB',      label: t('enum.targetType.JOB')      },
  ] as const;
}

/** Job 执行状态 */
export function getJobExecStatusOptions(t: TFunction) {
  return [
    { value: 'RUNNING',      label: t('enum.execStatus.RUNNING'),      color: 'blue'   },
    { value: 'SUCCESS',      label: t('enum.execStatus.SUCCESS'),      color: 'green'  },
    { value: 'PARTIAL_FAIL', label: t('enum.execStatus.PARTIAL_FAIL'), color: 'orange' },
    { value: 'FAILED',       label: t('enum.execStatus.FAILED'),       color: 'red'    },
  ] as const;
}

/** 评估模式 */
export function getEvalModeOptions(t: TFunction) {
  return [
    { value: 'PUSH' as const, label: t('enum.mode.PUSH') },
    { value: 'PULL' as const, label: t('enum.mode.PULL') },
  ] as const;
}

/** Actor 类型 */
export function getActorTypeOptions(t: TFunction) {
  return [
    { value: 'USER',   label: t('enum.actorType.USER')   },
    { value: 'SYSTEM', label: t('enum.actorType.SYSTEM') },
    { value: 'JOB',    label: t('enum.actorType.JOB')    },
  ] as const;
}

// ----- 工具函数 -----

export function labelOf(opts: ReadonlyArray<{ value: string; label: string }>, value: string): string {
  return opts.find((o) => o.value === value)?.label ?? value;
}

export function colorOf(opts: ReadonlyArray<{ value: string; label: string; color?: string }>, value: string): string | undefined {
  return opts.find((o) => o.value === value)?.color;
}
