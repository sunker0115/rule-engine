/**
 * i18n 翻译 key 类型定义 —— 所有命名空间的完整 key 集合。
 * 开发人员新增翻译时在这里扩展接口，IDE 自动补全所有 key。
 * 删除 key 时 TS 编译报错 → 找到引用位置 → 确认无残留。
 */

// ===== common 命名空间 =====
export interface CommonTranslation {
  app: { title: string };
  header: { actorLabel: string };
  tenant: { placeholder: string; notSelected: string; goSelect: string };
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
    id: string;
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
    tenant: string;
    searchPlaceholder: string;
    paginationTotal: string;
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
    enabled: string;
    disabled: string;
  };
  validation: {
    required: string;
    jsonFormat: string;
  };
  title: {
    tenantList: string;
  };
  menu: {
    tenants: string; scenes: string; rules: string;
    metrics: string; decisions: string;
    sessions: string; auditLogs: string;
    scheduledTasks: string; importExport: string;
    connectors: string; effectiveness: string;
  };
}

// ===== scene 命名空间 =====
export interface SceneTranslation {
  title: { list: string; detail: string };
  action: { create: string; detail: string };
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
    sensitive: string;
    exampleTitle: string;
    sourceNote: string;
    selectEventTypeFirst: string;
  };
  searchPlaceholder: string;
  edit: {
    title: string;
    noFields: string;
    fieldName: string;
    fieldType: string;
    fieldRequired: string;
    fieldSensitive: string;
    addField: string;
    addParam: string;
    paramName: string;
    paramValue: string;
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
    usage: string;
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
      connector: string; connectorPlaceholder: string; connectorEmpty: string;
      vars: string; varsHint: string; varsKeyPlaceholder: string;
      topic: string; keyExpr: string;
    };
    streamDisabled: string;
    breakingChangeTitle: string;
    breakingChangeContent: string;
  };
  searchPlaceholder: string;
  detail: { basicInfo: string; version: string; notFound: string };
  test: {
    title: string;
    sampleVars: string;
    sampleVarsHint: string;
    samplePayload: string;
    samplePayloadHint: string;
    sampleSubjectId: string;
    noInputs: string;
    run: string;
    invalidJson: string;
    result: string;
    renderedRequest: string;
    boundSql: string;
    rawResponse: string;
    successMatched: string;
    matched: string;
    notMatched: string;
    mappedValue: string;
    errorCode: string;
    success: string;
    failure: string;
    empty: string;
    errorMeaning: {
      PARSE_ERROR: string;
      UPSTREAM_ERROR: string;
      UNAUTHORIZED: string;
      TIMEOUT: string;
      NOT_FOUND: string;
      MAPPING_ERROR: string;
      TYPE_MISMATCH: string;
    };
  };
}

// ===== decision 命名空间 =====
export interface DecisionTranslation {
  title: { list: string };
  action: { create: string; edit: string; disable: string; enable: string };
  column: { code: string; name: string; priority: string; status: string; description: string; createdAt: string; updatedAt: string; usage: string };
  form: {
    code: string;
    codePlaceholder: string;
    codeDisabled: string;
    name: string;
    priority: string;
    priorityExtra: string;
    description: string;
  };
  detail: { basicInfo: string; sources: string; notFound: string };
}

// ===== rule 命名空间 =====
export interface RuleTranslation {
  title: { list: string; editor: string };
  action: {
    create: string; saveDraft: string; publish: string; dryRun: string;
    sessions: string;
    newVersion: string; rollback: string; disable: string; enable: string;
    deleteDraft: string; deleteRule: string;
  };
  searchPlaceholder: string;
  filter: { dateFrom: string; dateTo: string };
  triggerEventsPlaceholder: string;
  column: {
    code: string; name: string; kind: string; sceneCode: string;
    status: string; currentVersion: string; publishedAt: string; actions: string;
    actionsDetail: string; actionsEdit: string;
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
    leftPanel: { ruleInfo: string; versionTimeline: string; executorLabel: string; dividerPublish: string; dividerManage: string; dryRunVersion: string };
    versionContent: { title: string; version: string; conditionAst: string; decisionBindings: string; preGates: string; triggerEventTypes: string; script: string; publishedBy: string };
    versionDiff: { title: string; hint: string };
    versionRestore: { title: string; confirm: string; created: string; blockedByDraft: string };
    centerPanel: { placeholder: string };
    rightPanel: {
      property: string; preGate: string; decisionBinding: string;
      noSelection: string; executor: string;
    };
    notFound: string;
    conditionCard: {
      selectType: string; metric: string; payload: string;
      selectMetric: string; payloadField: string;
      valueRefOptions: { metric: string; payload: string };
    };
    groupEditor: {
      and: string; or: string; not: string; unNot: string;
      deleteGroup: string; addCondition: string; addGroup: string;
      wrapNot: string;
      descriptionAnd: string; descriptionOr: string;
      emptyHint: string;
    };
    conditionTree: {
      emptyHint: string; addFirst: string;
    };
    scorecard: {
      threshold: string; thresholdHint: string;
      addItem: string; emptyHint: string;
      weight: string;
      bandsTitle: string; bandsEmptyHint: string;
      bandMin: string; bandMax: string;
      bandDecision: string; bandCategory: string;
      addBand: string; bandOverlap: string;
    };
    decisionTree: {
      condition: string;
      then: string; else: string;
      selectDecision: string; toLeaf: string; toBranch: string;
      addElse: string; removeElse: string;
    };
    decisionTable: {
      addColumn: string; addRow: string;
      emptyRowHint: string; metric: string; deleteColumn: string;
      cellPlaceholder: string; decisionCode: string;
      decisionPlaceholder: string; deleteRowConfirm: string;
    };
    script: {
      placeholder: string;
      syntaxHints: Record<string, string>;
    };
    createModal: {
      code: string; name: string; kind: string;
      scene: string; scenePlaceholder: string;
      triggerEvents: string; triggerEventsPlaceholder: string;
      scriptLang: string; scriptSource: string;
      scriptSourcePlaceholder: string; scriptSourceDefault: string; scriptSourceDeferHint: string;
    };
  };
  conditionType: {
    EQ: string; NEQ: string; GT: string; GTE: string; LT: string; LTE: string;
    IN: string; NOT_IN: string; BETWEEN: string; NOT_BETWEEN: string;
    CONTAINS: string; NOT_CONTAINS: string;
    STARTS_WITH: string; ENDS_WITH: string; MATCHES: string;
    DATE_BEFORE: string; DATE_AFTER: string;
    time_window: string; time_occurred_at: string;
  };
  param: {
    widget: {
      threshold: string; min: string; max: string;
      values: string; element: string; prefix: string; suffix: string;
      regex: string; operator: string; start: string; end: string;
      value: string; timezone: string; datesExclude: string; daysOfWeek: string;
    };
    operatorBefore: string; operatorAfter: string; operatorBetween: string;
  };
  detail: {
    title: string; basicInfo: string; versionHistory: string;
    label: {
      scene: string; triggerEvents: string; decision: string; preGate: string;
      activeVersion: string; draftVersion: string; noVersion: string;
    };
  };
  preGate: {
    descPercent: string; descBucket: string;
    modePercent: string; modeBucket: string;
    labelPercentage: string; labelBucketStart: string; labelBucketEnd: string;
    labelRange: string; labelRollout: string;
    na: string;
    rolloutTitle: string; timeWindowTitle: string; descTimeWindow: string;
  };
  decisionBinding: {
    selectPlaceholder: string;
    addButton: string; singleOnlyHint: string;
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
  title: { dryRun: string; sessionList: string; sessionDetail: string; ruleSessions: string };
  replay: {
    button: string; resultTitle: string; consistencyHint: string; ruleHit: string;
  };
  dryRun: {
    execute: string;
    targetVersion: string;
    eventType: string;
    eventTypeAny: string;
    subjectId: string;
    payload: string;
    payloadHint: string;
    addField: string;
    copyJson: string;
    copiedJson: string;
    field: string;
    key: string;
    value: string;
    result: {
      hit: string; miss: string; blocked: string;
      hitDecisions: string; finalDecision: string; nodeTrace: string;
    };
  };
  enum: {
    sessionStatus: {
      HIT: string; MISS: string; BLOCKED: string;
      ERROR: string; PENDING: string; FAILED: string;
    };
    source: { HTTP: string; MQ: string; JOB: string; SDK: string; REPLAY: string };
    mode: { PUSH: string; PULL: string };
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
      notFound: string;
      blockedBy: string; errorCode: string; score: string; category: string;
      startedAt: string; finishedAt: string;
    };
  };
  trace: {
    expandAll: string; collapseAll: string; copyJson: string;
    nodeSatisfied: string; nodeUnsatisfied: string; nodeSkipped: string; nodeError: string;
    preGateBlocked: string;
    noData: string;
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
      CREATE: string; UPDATE: string; PUBLISH: string;
      ENABLE: string; DISABLE: string; DELETE: string; IMPORT: string;
    };
    targetType: { RULE: string; SCENE: string; METRIC: string; DECISION: string; JOB: string };
  };
  diff: { before: string; after: string; expand: string; noDiff: string; renderError: string; calcError: string; noSnapshot: string };
}

// ===== scheduledTask 命名空间 =====
export interface ScheduledTaskTranslation {
  title: { list: string; detail: string };
  notice: string;
  action: { trigger: string; viewDetail: string; enable: string; disable: string; createIngestion: string };
  triggerSuccess: string;
  create: {
    title: string;
    createSuccess: string;
    createFailed: string;
    selectTenant: string;
    submit: string;
    field: {
      code: string; codeRequired: string; codeExtra: string;
      name: string; nameRequired: string;
      cron: string; cronRequired: string; cronExtra: string;
      datasource: string; datasourceRequired: string; datasourceExtra: string;
      sql: string; sqlRequired: string; sqlExtra: string;
    };
  };
  column: {
    name: string; code: string; taskType: string;
    cronExpr: string; status: string; actions: string;
  };
  /** 任务类型 label —— 已知类型给中文/英文名，未知类型由页面兜底显示原始串 */
  type: { trigger: string; ingestion: string };
  enum: {
    status: { ACTIVE: string; DISABLED: string };
    execStatus: { RUNNING: string; SUCCESS: string; PARTIAL_FAIL: string; FAILED: string };
  };
  detail: { basicInfo: string; config: string };
  execution: {
    title: string;
    triggerConfirm: string;
    column: {
      id: string; triggerAt: string; finishedAt: string;
      processedCount: string; successCount: string; errorCount: string;
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
    downloadComplete: string; sceneCodePlaceholder: string; ruleIdsPlaceholder: string;
  };
  import: {
    upload: string; uploadHint: string;
    preview: string; previewTitle: string;
    existing: string; newDraft: string; skip: string; review: string;
    importComplete: string; previewAndImport: string;
    execute: string;
    policy: {
      label: string; skip: string; overwrite: string; abort: string;
      hint: { skip: string; overwrite: string; abort: string };
    };
    diff: {
      title: string; cancel: string; confirm: string;
      willCreate: string; willOverwrite: string; skipped: string; conflicts: string;
      ruleCode: string; sceneCode: string; reason: string;
      conflictType: string; detail: string;
      conflictHint: string; abortHint: string;
    };
    result: {
      title: string; willCreate: string; willOverwrite: string; skipped: string;
      rulesImported: string; scenesCreated: string; scenesSkipped: string;
      metricsCreated: string; metricsSkipped: string;
      metricsReview: string; decisionsCreated: string; decisionsSkipped: string;
    };
    error: { parseError: string; missingScene: string; dryRunFailed: string; applyFailed: string; conflicts: string };
  };
}

// ===== 所有命名空间聚合（供 i18next CustomTypeOptions 使用）=====
// ===== connector 命名空间 =====
export interface ConnectorTranslation {
  title: { list: string; detail: string; create: string; edit: string };
  action: { create: string; addQuery: string; addHeader: string; addError: string; addScope: string; disable: string; disableConfirm: string };
  column: {
    connectorCode: string;
    name: string;
    status: string;
    action: string;
  };
  searchPlaceholder: string;
  detail: { notFound: string };
  section: {
    basic: string;
    request: string;
    response: string;
    auth: string;
    resilience: string;
    errorMapping: string;
  };
  form: {
    connectorCode: string;
    connectorCodePlaceholder: string;
    name: string;
    endpointRef: string;
    endpointRefExtra: string;
    method: string;
    pathTemplate: string;
    pathTemplatePlaceholder: string;
    query: string;
    headers: string;
    paramName: string;
    paramValue: string;
    bodyTemplate: string;
    bodyTemplatePlaceholder: string;
    successWhen: string;
    successPath: string;
    successOp: string;
    successValue: string;
    valuePath: string;
    valuePathPlaceholder: string;
    authKind: string;
    headerName: string;
    credentialRef: string;
    tokenRef: string;
    tokenUrl: string;
    clientIdRef: string;
    clientSecretRef: string;
    scopes: string;
    connectTimeoutMs: string;
    readTimeoutMs: string;
    retries: string;
    retryOn: string;
    enableCircuitBreaker: string;
    failureRateThreshold: string;
    windowSeconds: string;
    openSeconds: string;
    errorWhenStatusFrom: string;
    errorWhenStatusTo: string;
    errorWhenEnvelopeCode: string;
    errorTo: string;
  };
  enum: {
    httpMethod: { GET: string; POST: string; PUT: string };
    compareOp: { EQ: string; NE: string; GT: string; GE: string; LT: string; LE: string };
    authKind: { NONE: string; STATIC_HEADER: string; BEARER: string; OAUTH2_CLIENT_CREDENTIALS: string };
    retryTrigger: { TIMEOUT: string; UPSTREAM_5XX: string };
  };
  preset: {
    title: string;
    hint: string;
    codeMsgData: string;
    bareJson: string;
    successData: string;
    applied: string;
  };
  test: {
    title: string;
    saveFirst: string;
    sampleVars: string;
    sampleVarsHint: string;
    samplePayload: string;
    samplePayloadHint: string;
    sampleSubjectId: string;
    noInputs: string;
    run: string;
    invalidJson: string;
    result: string;
    renderedRequest: string;
    boundSql: string;
    rawResponse: string;
    successMatched: string;
    matched: string;
    notMatched: string;
    mappedValue: string;
    errorCode: string;
    success: string;
    failure: string;
    empty: string;
    errorMeaning: {
      PARSE_ERROR: string;
      UPSTREAM_ERROR: string;
      UNAUTHORIZED: string;
      TIMEOUT: string;
      NOT_FOUND: string;
      MAPPING_ERROR: string;
      TYPE_MISMATCH: string;
    };
  };
}

// ===== analysis 命名空间（规则集静态分析）=====
export interface RuleSetAnalysisTranslation {
  button: string;
  title: string;
  reanalyze: string;
  empty: string;
  loadError: string;
  summaryBarTooltip: string;
  summaryBarHint: string;
  allClear: string;
  group: {
    incoherences: string;
    deadRules: string;
    conflicts: string;
    coverageGaps: string;
    overlaps: string;
    unanalyzable: string;
    redundancies: string;
  };
  unanalyzableNote: string;
  sevTag: { ERROR: string; WARN: string; INFO: string; SKIP: string };
}

// ===== lineage 命名空间（产出 / 引用血缘）=====
export interface LineageTranslation {
  drawerTitle: string;
  metricDrawerTitle: string;
  count: string;
  empty: string;
  toEditor: string;
  badge: string;
  col: { ruleCode: string; ruleName: string; scene: string; status: string };
  editorChip: string;
  disableGuardTitle: string;
  disableGuardConfirm: string;
}

// ===== effectiveness 命名空间（决策效果报表 B32）=====
export interface EffectivenessTranslation {
  title: string;
  valueGate: string;
  filter: {
    scene: string;
    scenePlaceholder: string;
    timeRange: string;
    positiveLabels: string;
    positiveLabelsPlaceholder: string;
    dimension: string;
    bucket: string;
    query: string;
    backfill: string;
  };
  dimension: { RULE_VERSION: string; DECISION: string };
  bucket: { NONE: string; DAY: string; WEEK: string };
  banner: {
    title: string;
    totalSessions: string;
    labeled: string;
    unlabeled: string;
    blocked: string;
    positive: string;
    negative: string;
    note: string;
  };
  table: {
    title: string;
    bucket: string;
    dimensionKey: string;
    tp: string; fp: string; fn: string; tn: string;
    precision: string; recall: string; fireRate: string; firedTotal: string;
  };
  chart: {
    title: string;
    metric: string;
    precision: string; recall: string; fireRate: string;
    needBucket: string;
  };
  empty: string;
  validation: { sceneRequired: string; rangeRequired: string };
  modal: {
    title: string;
    tenant: string;
    eventId: string;
    outcomeLabel: string;
    outcomeValue: string;
    labeledAt: string;
    source: string;
    note: string;
    addRow: string;
    submit: string;
    cancel: string;
    accepted: string;
  };
}

export interface TranslationResources {
  common: CommonTranslation;
  effectiveness: EffectivenessTranslation;
  scene: SceneTranslation;
  metric: MetricTranslation;
  decision: DecisionTranslation;
  rule: RuleTranslation;
  eval: EvalTranslation;
  audit: AuditTranslation;
  scheduledTask: ScheduledTaskTranslation;
  importExport: ImportExportTranslation;
  connector: ConnectorTranslation;
  analysis: RuleSetAnalysisTranslation;
  lineage: LineageTranslation;
}
