/** 路由路径常量 —— 全应用唯一真相源 */
export const ROUTES = {
  SCENES:         '/scenes',
  SCENE_DETAIL:   '/scenes/:sceneCode',
  SCENE_RULES:    '/scenes/:sceneCode/rules',
  RULE_EDITOR:    '/scenes/:sceneCode/rules/:ruleId',
  METRICS:        '/metrics',
  METRIC_DETAIL:  '/metrics/:metricCode',
  DECISIONS:      '/decisions',
  SESSIONS:       '/sessions',
  SESSION_DETAIL: '/sessions/:sessionId',
  AUDIT_LOGS:     '/audit-logs',
  JOBS:           '/jobs',
  JOB_DETAIL:     '/jobs/:jobId',
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
