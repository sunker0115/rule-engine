const ADMIN = '/admin/v1';
const API   = '/api/v1/rule';

export const ENDPOINTS = {
  // Scene
  SCENE_LIST:    `${ADMIN}/scenes`,
  SCENE_DETAIL:  (sceneCode: string) => `${ADMIN}/scenes/${sceneCode}`,
  SCENE_CREATE:  `${ADMIN}/scenes`,
  SCENE_TOGGLE_STATUS: (sceneCode: string) => `${ADMIN}/scenes/${sceneCode}/status`,

  // Rule
  RULE_LIST:     `${ADMIN}/rules`,
  RULE_DETAIL:   (id: number) => `${ADMIN}/rules/${id}`,
  RULE_CREATE:   `${ADMIN}/rules`,
  RULE_DRAFT:    (id: number) => `${ADMIN}/rules/${id}/draft`,
  RULE_PUBLISH:  (id: number) => `${ADMIN}/rules/${id}/publish`,
  RULE_DISABLE:  (id: number) => `${ADMIN}/rules/${id}/disable`,
  RULE_VERSIONS: (id: number) => `${ADMIN}/rules/${id}/versions`,
  RULE_DELETE:   (id: number) => `${ADMIN}/rules/${id}`,
  RULE_DELETE_VERSION: (ruleId: number, versionId: number) => `${ADMIN}/rules/${ruleId}/versions/${versionId}`,
  RULE_EXPORT:   `${ADMIN}/rules/export`,
  RULE_IMPORT:   `${ADMIN}/rules/import`,

  // Metric
  METRIC_LIST:   `${ADMIN}/metrics`,
  METRIC_CREATE: `${ADMIN}/metrics`,
  METRIC_UPDATE: (code: string) => `${ADMIN}/metrics/${code}`,
  METRIC_TOGGLE_STATUS: (code: string) => `${ADMIN}/metrics/${code}/status`,
  METRIC_IMPACT: (code: string, version: number) => `${ADMIN}/metrics/${code}/versions/${version}/impact`,

  // Decision
  DECISION_LIST:   `${ADMIN}/decisions`,
  DECISION_CREATE: `${ADMIN}/decisions`,

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
  RULE_SESSIONS:       (ruleId: number) => `${ADMIN}/rules/${ruleId}/sessions`,

  // Audit
  AUDIT_LOG_LIST: `${ADMIN}/audit-logs`,

  // Job
  JOB_LIST:       `${ADMIN}/jobs`,
  JOB_DETAIL:     (jobId: number) => `${ADMIN}/jobs/${jobId}`,
  JOB_TRIGGER:    (jobId: number) => `${ADMIN}/jobs/${jobId}/trigger`,
  JOB_EXECUTIONS: (jobId: number) => `${ADMIN}/jobs/${jobId}/executions`,

  // Tenant
  TENANT_LIST: `${ADMIN}/tenants`,
  TENANT_TOGGLE_STATUS: (id: number) => `${ADMIN}/tenants/${id}/status`,
} as const;
