/**
 * i18n 翻译 key 类型定义 —— 所有命名空间的完整 key 集合。
 * 开发人员新增翻译时在这里扩展接口，IDE 自动补全所有 key。
 * 删除 key 时 TS 编译报错 → 找到引用位置 → 确认无残留。
 */

// ===== common 命名空间 =====
export interface CommonTranslation {
  app: { title: string };
  header: { actorLabel: string };
  tenant: { placeholder: string };
  button: {
    back: string;
    save: string;
    cancel: string;
    edit: string;
    delete: string;
    confirm: string;
    submit: string;
    refresh: string;
    copy: string;
  };
  label: {
    code: string;
    name: string;
    status: string;
    description: string;
    actions: string;
    createdAt: string;
    updatedAt: string;
    none: string;
    yes: string;
    no: string;
    all: string;
  };
  enum: {
    status: { ACTIVE: string; DISABLED: string };
    actorType: { USER: string; SYSTEM: string; JOB: string };
  };
  message: {
    createSuccess: string;
    updateSuccess: string;
    deleteSuccess: string;
    saveSuccess: string;
    loadError: string;
    confirmDelete: string;
  };
  validation: {
    required: string;
    jsonFormat: string;
  };
}

// ===== scene 命名空间 =====
export interface SceneTranslation {
  title: { list: string; detail: string };
  action: { create: string };
  column: {
    sceneCode: string;
    name: string;
    dominantMode: string;
    subjectType: string;
    status: string;
    actions: string;
  };
  enum: {
    dominantMode: { PUSH: string; PULL: string; HYBRID: string };
  };
  form: {
    code: string;
    codePlaceholder: string;
    name: string;
    dominantMode: string;
    subjectType: string;
    description: string;
    payloadSchema: string;
    payloadSchemaExtra: string;
    eventTypes: string;
    eventTypesPlaceholder: string;
    defaultParams: string;
    defaultParamsExtra: string;
    decisionStrategy: string;
    status: string;
  };
  detail: {
    basicInfo: string;
    inputManifest: string;
    ruleList: string;
    notFound: string;
  };
  inputManifest: {
    info: string;
    filterEventType: string;
    filterAll: string;
    column: { name: string; dataType: string; required: string };
    required: string;
    optional: string;
    exampleTitle: string;
  };
}

// ===== metric 命名空间 =====
export interface MetricTranslation {
  title: { list: string; detail: string };
  action: { create: string; queryImpact: string };
  column: {
    metricCode: string;
    name: string;
    sourceType: string;
    dataType: string;
    version: string;
    allowProvided: string;
    cacheTtl: string;
    status: string;
  };
  enum: {
    sourceType: {
      ATTRIBUTE: string;
      SQL_AGGREGATE: string;
      EXTERNAL_HTTP: string;
      STREAM: string;
    };
    dataType: {
      LONG: string; DOUBLE: string; STRING: string;
      BOOLEAN: string; LIST: string; DATE: string; DATETIME: string;
    };
  };
  form: {
    code: string;
    codePlaceholder: string;
    name: string;
    sourceType: string;
    dataType: string;
    cacheTtl: string;
    allowProvided: string;
    params: {
      table: string; column: string;
      datasource: string; sql: string;
      endpoint: string; path: string; pathPlaceholder: string;
      jsonPath: string; jsonPathPlaceholder: string;
      topic: string; keyExpr: string;
    };
    streamDisabled: string;
    breakingChangeTitle: string;
    breakingChangeContent: string;
  };
  detail: { notFound: string };
  impact: {
    column: { ruleCode: string; ruleName: string; sceneCode: string; status: string };
  };
}

// ===== decision 命名空间 =====
export interface DecisionTranslation {
  title: { list: string };
  action: { create: string; edit: string };
  column: { code: string; name: string; priority: string; description: string; createdAt: string };
  form: {
    code: string;
    codePlaceholder: string;
    codeDisabled: string;
    name: string;
    priority: string;
    priorityExtra: string;
    description: string;
  };
}

// ===== rule 命名空间 =====
export interface RuleTranslation {
  title: { list: string; editor: string };
  action: {
    create: string; saveDraft: string; publish: string; dryRun: string;
    newVersion: string; rollback: string; disable: string; enable: string;
    deleteDraft: string; deleteRule: string;
  };
  column: {
    code: string; name: string; kind: string; sceneCode: string;
    status: string; currentVersion: string; publishedAt: string; actions: string;
  };
  enum: {
    status: { DRAFT: string; PUBLISHED: string; DISABLED: string };
    versionStatus: { DRAFT: string; ACTIVE: string; SUPERSEDED: string };
    kind: {
      AST_BOOLEAN: string; SCORECARD: string;
      DECISION_TREE: string; DECISION_TABLE: string; EXPRESSION_SCRIPT: string;
    };
  };
  editor: {
    leftPanel: { ruleInfo: string; versionTimeline: string };
    centerPanel: { placeholder: string };
    rightPanel: {
      property: string; preGate: string; decisionBinding: string;
      noSelection: string;
    };
  };
  version: {
    rollbackConfirm: string;
    deleteDraftConfirm: string;
    deleteRuleConfirm: string;
    publishConfirm: string;
    disableConfirm: string;
    newVersionConfirm: string;
  };
}

// ===== eval 命名空间 =====
export interface EvalTranslation {
  title: { dryRun: string; sessionList: string; sessionDetail: string };
  dryRun: {
    execute: string;
    targetVersion: string;
    eventType: string;
    subjectId: string;
    payload: string;
    result: {
      hit: string; miss: string; blocked: string;
      hitDecisions: string; finalDecision: string; nodeTrace: string;
    };
  };
  session: {
    column: {
      sessionId: string; eventId: string; sceneCode: string;
      eventType: string; subjectId: string; status: string;
      finalDecision: string; candidateRuleCount: string; hitRuleCount: string;
      source: string; mode: string; evalDuration: string; occurredAt: string;
    };
    filter: {
      sceneCode: string; subjectId: string; eventId: string;
      status: string; source: string; from: string; to: string;
    };
    detail: {
      basicInfo: string; traceTree: string; hitRules: string;
      contextSnapshot: string;
    };
  };
  trace: {
    expandAll: string; collapseAll: string; copyJson: string;
    nodeSatisfied: string; nodeUnsatisfied: string; nodeSkipped: string; nodeError: string;
    preGateBlocked: string;
  };
}

// ===== audit 命名空间 =====
export interface AuditTranslation {
  title: { list: string };
  column: {
    actor: string; actorType: string; action: string;
    targetType: string; targetId: string; operatedAt: string;
  };
  filter: {
    targetType: string; targetId: string; actor: string;
    action: string; from: string; to: string;
  };
  enum: {
    action: {
      CREATE: string; UPDATE: string; PUBLISH: string; PUBLISH_FAILED: string;
      ENABLE: string; DISABLE: string; DELETE: string; IMPORT: string;
    };
    targetType: { RULE: string; SCENE: string; METRIC: string; DECISION: string; JOB: string };
  };
  diff: { before: string; after: string; expand: string };
}

// ===== job 命名空间 =====
export interface JobTranslation {
  title: { list: string; detail: string };
  notice: string;
  action: { trigger: string; viewDetail: string; enable: string; disable: string };
  column: {
    name: string; code: string; sceneCode: string; eventType: string;
    cronExpr: string; status: string; subjectQueryType: string; actions: string;
  };
  enum: {
    execStatus: { RUNNING: string; SUCCESS: string; PARTIAL_FAIL: string; FAILED: string };
  };
  execution: {
    title: string;
    triggerConfirm: string;
    column: {
      id: string; triggerAt: string; finishedAt: string;
      subjectCount: string; successCount: string; errorCount: string;
      status: string; errorSummary: string;
    };
  };
}

// ===== import-export 命名空间 =====
export interface ImportExportTranslation {
  title: { page: string };
  tab: { export: string; import: string };
  export: {
    scope: string; byRuleIds: string; byScene: string; all: string;
    download: string; summary: string;
  };
  import: {
    upload: string; uploadHint: string;
    preview: string; previewTitle: string;
    existing: string; newDraft: string; skip: string; review: string;
    execute: string;
    result: {
      title: string; rulesImported: string; scenesCreated: string;
      scenesSkipped: string; metricsCreated: string; metricsSkipped: string;
      metricsReview: string; decisionsCreated: string; decisionsSkipped: string;
    };
    error: { parseError: string; missingScene: string };
  };
}

// ===== 所有命名空间聚合（供 i18next CustomTypeOptions 使用）=====
export interface TranslationResources {
  common: CommonTranslation;
  scene: SceneTranslation;
  metric: MetricTranslation;
  decision: DecisionTranslation;
  rule: RuleTranslation;
  eval: EvalTranslation;
  audit: AuditTranslation;
  job: JobTranslation;
  importExport: ImportExportTranslation;
}
