/** 路由路径常量 —— 全应用唯一真相源 */
export const ROUTES = {
  TENANTS:        '/tenants',
  SCENES:         '/scenes',
  SCENE_DETAIL:   '/scenes/:sceneCode',
  SCENE_EDIT:     '/scenes/:sceneCode/edit',
  SCENE_RULES:    '/scenes/:sceneCode/rules',
  RULES:          '/rules',
  RULE_EDITOR:    '/rule-editor/:ruleId',
  METRICS:        '/metrics',
  METRIC_DETAIL:  '/metrics/:metricCode',
  CONNECTORS:     '/connectors',
  CONNECTOR_NEW:  '/connectors/new',
  CONNECTOR_DETAIL: '/connectors/:connectorCode',
  DECISIONS:      '/decisions',
  DECISION_DETAIL: '/decisions/:code',
  SESSIONS:       '/sessions',
  SESSION_DETAIL: '/sessions/:sessionId',
  AUDIT_LOGS:     '/audit-logs',
  SCHEDULED_TASKS:        '/scheduled-tasks',
  SCHEDULED_TASK_DETAIL:  '/scheduled-tasks/:taskId',
  IMPORT_EXPORT:  '/import-export',
} as const;

/** 生成带参数的实际路径 */
export function route(path: string, params?: Record<string, string | number>): string {
  let result: string = path;
  if (params) {
    for (const [key, value] of Object.entries(params)) {
      result = result.replace(`:${key}`, String(value));
    }
  }
  return result;
}
