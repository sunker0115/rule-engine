/** 通用状态 */
export const STATUS_OPTIONS = [
  { value: 'ACTIVE',   label: '启用',   color: 'green'  },
  { value: 'DISABLED', label: '禁用',   color: 'red'    },
] as const;

/** Scene 使用模式 */
export const DOMINANT_MODE_OPTIONS = [
  { value: 'PUSH',   label: 'PUSH (异步)' },
  { value: 'PULL',   label: 'PULL (同步)' },
  { value: 'HYBRID', label: 'HYBRID (混合)' },
] as const;

/** 规则状态 */
export const RULE_STATUS_OPTIONS = [
  { value: 'DRAFT',     label: '草稿',     color: 'blue'   },
  { value: 'PUBLISHED', label: '已发布',   color: 'green'  },
  { value: 'DISABLED',  label: '已禁用',   color: 'red'    },
] as const;

/** 版本状态 */
export const VERSION_STATUS_OPTIONS = [
  { value: 'DRAFT',      label: '草稿',     color: 'blue'    },
  { value: 'ACTIVE',     label: '生效中',   color: 'green'   },
  { value: 'SUPERSEDED', label: '已取代',   color: 'default' },
] as const;

/** Metric 取数方式 */
export const SOURCE_TYPE_OPTIONS = [
  { value: 'ATTRIBUTE',     label: '属性表'      },
  { value: 'SQL_AGGREGATE', label: 'SQL 聚合'     },
  { value: 'EXTERNAL_HTTP', label: '外部 HTTP'    },
  { value: 'STREAM',        label: '流处理'       },
] as const;

/** Metric 数据类型 */
export const DATA_TYPE_OPTIONS = [
  { value: 'LONG',     label: '整数'     },
  { value: 'DOUBLE',   label: '浮点'    },
  { value: 'STRING',   label: '字符串'  },
  { value: 'BOOLEAN',  label: '布尔'    },
  { value: 'LIST',     label: '列表'    },
  { value: 'DATE',     label: '日期'    },
  { value: 'DATETIME', label: '日期时间' },
] as const;

/** 规则 kind */
export const RULE_KIND_OPTIONS = [
  { value: 'AST_BOOLEAN',        label: 'AST 布尔树'       },
  { value: 'SCORECARD',          label: '评分卡'           },
  { value: 'DECISION_TREE',      label: '决策树'           },
  { value: 'DECISION_TABLE',     label: '决策表'           },
  { value: 'EXPRESSION_SCRIPT',  label: '表达式脚本'       },
] as const;

/** 评估会话状态 */
export const SESSION_STATUS_OPTIONS = [
  { value: 'HIT',     label: '命中',    color: 'green'   },
  { value: 'MISS',    label: '未命中',  color: 'default'  },
  { value: 'BLOCKED', label: '被拦截',  color: 'orange'  },
  { value: 'ERROR',   label: '错误',    color: 'red'     },
  { value: 'PENDING', label: '进行中',  color: 'blue'    },
  { value: 'FAILED',  label: '失败',    color: '#8b0000' },
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
  { value: 'CREATE',         label: '创建',         color: 'blue'    },
  { value: 'UPDATE',         label: '更新',         color: 'blue'    },
  { value: 'PUBLISH',        label: '发布',         color: 'green'   },
  { value: 'PUBLISH_FAILED', label: '发布失败',     color: 'red'     },
  { value: 'ENABLE',         label: '启用',         color: 'green'   },
  { value: 'DISABLE',        label: '禁用',         color: 'orange'  },
  { value: 'DELETE',         label: '删除',         color: 'red'     },
  { value: 'IMPORT',         label: '导入',         color: 'purple'  },
] as const;

/** 审计目标类型 */
export const AUDIT_TARGET_TYPE_OPTIONS = [
  { value: 'RULE',     label: '规则'   },
  { value: 'SCENE',    label: '场景'   },
  { value: 'METRIC',   label: '指标'   },
  { value: 'DECISION', label: '决策'   },
  { value: 'JOB',      label: '任务'    },
] as const;

/** Job 执行状态 */
export const JOB_EXEC_STATUS_OPTIONS = [
  { value: 'RUNNING',      label: '运行中',      color: 'blue'   },
  { value: 'SUCCESS',      label: '成功',        color: 'green'  },
  { value: 'PARTIAL_FAIL', label: '部分失败',    color: 'orange' },
  { value: 'FAILED',       label: '失败',        color: 'red'    },
] as const;

/** 评估模式 */
export const EVAL_MODE_OPTIONS = [
  { value: 'PUSH', label: 'PUSH' },
  { value: 'PULL', label: 'PULL' },
] as const;

/** Actor 类型 */
export const ACTOR_TYPE_OPTIONS = [
  { value: 'USER',   label: '用户'   },
  { value: 'SYSTEM', label: '系统'   },
  { value: 'JOB',    label: '任务'    },
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
