const ADMIN = '/admin/v1';
const API   = '/api/v1/rule';

export const ENDPOINTS = {
  // Scene
  SCENE_LIST:    `${ADMIN}/scenes`,
  SCENE_DETAIL:  (sceneCode: string) => `${ADMIN}/scenes/${sceneCode}`,
  SCENE_CREATE:  `${ADMIN}/scenes`,
  SCENE_TOGGLE_STATUS: (sceneCode: string) => `${ADMIN}/scenes/${sceneCode}/status`,
  SCENE_ANALYSIS: (sceneCode: string) => `${ADMIN}/scenes/${sceneCode}/analysis`,

  // Rule
  RULE_LIST:     `${ADMIN}/rules`,
  RULE_DETAIL:   (id: number) => `${ADMIN}/rules/${id}`,
  RULE_CREATE:   `${ADMIN}/rules`,
  RULE_DRAFT:    (id: number) => `${ADMIN}/rules/${id}/draft`,
  RULE_PUBLISH:  (id: number) => `${ADMIN}/rules/${id}/publish`,
  RULE_DISABLE:  (id: number) => `${ADMIN}/rules/${id}/disable`,
  RULE_ENABLE:   (id: number) => `${ADMIN}/rules/${id}/enable`,
  RULE_VERSIONS: (id: number) => `${ADMIN}/rules/${id}/versions`,
  RULE_DELETE:   (id: number) => `${ADMIN}/rules/${id}`,
  RULE_DELETE_VERSION: (ruleId: number, versionId: number) => `${ADMIN}/rules/${ruleId}/versions/${versionId}`,
  RULE_VERSION:  (ruleId: number, versionId: number) => `${ADMIN}/rules/${ruleId}/versions/${versionId}`,
  RULE_EXPORT:   `${ADMIN}/rules/export`,
  RULE_IMPORT:   `${ADMIN}/rules/import`,

  // Metric
  METRIC_LIST:   `${ADMIN}/metrics`,
  METRIC_CREATE: `${ADMIN}/metrics`,
  METRIC_DETAIL: (code: string) => `${ADMIN}/metrics/${code}`,
  METRIC_UPDATE: (code: string) => `${ADMIN}/metrics/${code}`,
  METRIC_TOGGLE_STATUS: (code: string) => `${ADMIN}/metrics/${code}/status`,
  METRIC_IMPACT: (code: string, version: number) => `${ADMIN}/metrics/${code}/versions/${version}/impact`,
  METRIC_SOURCES: (code: string) => `${ADMIN}/metrics/${code}/sources`,
  METRIC_TEST:   (code: string) => `${ADMIN}/metrics/${code}:test`,

  // Connector
  CONNECTORS:        `${ADMIN}/connectors`,
  CONNECTOR_DETAIL:  (code: string) => `${ADMIN}/connectors/${code}`,
  CONNECTOR_UPDATE:  (code: string) => `${ADMIN}/connectors/${code}`,
  CONNECTOR_DISABLE: (code: string) => `${ADMIN}/connectors/${code}/disable`,
  CONNECTOR_TEST:    (code: string) => `${ADMIN}/connectors/${code}:test`,

  // Decision
  DECISION_LIST:   `${ADMIN}/decisions`,
  DECISION_CREATE: `${ADMIN}/decisions`,
  DECISION_GET:           (code: string) => `${ADMIN}/decisions/${code}`,
  DECISION_DISABLE:       (code: string) => `${ADMIN}/decisions/${code}/disable`,
  DECISION_ENABLE:        (code: string) => `${ADMIN}/decisions/${code}/enable`,
  DECISION_SOURCES:       (code: string) => `${ADMIN}/decisions/${code}/sources`,
  DECISION_USAGE_COUNTS:  `${ADMIN}/decisions/usage-counts`,
  METRIC_USAGE_COUNTS:    `${ADMIN}/metrics/usage-counts`,

  // Metadata
  SCENE_METADATA: (sceneCode: string) => `${ADMIN}/scenes/${sceneCode}/metadata`,

  // Evaluation
  EVAL_EVENT:    `${API}/event`,
  EVAL_EVALUATE: `${API}/evaluate`,
  EVAL_DRY_RUN:  `${API}/dry-run`,

  // Input Manifest
  INPUT_MANIFEST: (sceneCode: string) => `${API}/scenes/${sceneCode}/input-manifest`,

  // Evaluation Sessions
  SESSION_LIST:        `${ADMIN}/evaluation-sessions`,
  SESSION_DETAIL:      (sessionId: number) => `${ADMIN}/evaluation-sessions/${sessionId}`,
  SESSION_TRACE_TREE:  (sessionId: number) => `${ADMIN}/evaluation-sessions/${sessionId}/trace/tree`,
  SESSION_REPLAY:      (sessionId: number) => `${ADMIN}/evaluation-sessions/${sessionId}/replay`,
  RULE_SESSIONS:       (ruleId: number) => `${ADMIN}/rules/${ruleId}/sessions`,

  // Audit
  AUDIT_LOG_LIST: `${ADMIN}/audit-logs`,

  // Decision Effectiveness (B32)
  EFFECTIVENESS:      `${ADMIN}/decision-outcomes/effectiveness`,
  DECISION_OUTCOMES:  `${ADMIN}/decision-outcomes`,

  // Scheduled Task
  SCHEDULED_TASK_LIST:       `${ADMIN}/scheduled-tasks`,
  SCHEDULED_TASK_CREATE:     `${ADMIN}/scheduled-tasks`,
  SCHEDULED_TASK_DETAIL:     (taskId: number) => `${ADMIN}/scheduled-tasks/${taskId}`,
  SCHEDULED_TASK_TRIGGER:    (taskId: number) => `${ADMIN}/scheduled-tasks/${taskId}/trigger`,
  SCHEDULED_TASK_ENABLE:     (taskId: number) => `${ADMIN}/scheduled-tasks/${taskId}/enable`,
  SCHEDULED_TASK_DISABLE:    (taskId: number) => `${ADMIN}/scheduled-tasks/${taskId}/disable`,
  SCHEDULED_TASK_EXECUTIONS: (taskId: number) => `${ADMIN}/scheduled-tasks/${taskId}/executions`,
  DATASOURCE_LIST: `${ADMIN}/datasources`,

  // Tenant
  TENANT_LIST: `${ADMIN}/tenants`,
  TENANT_TOGGLE_STATUS: (id: number) => `${ADMIN}/tenants/${id}/status`,
} as const;
