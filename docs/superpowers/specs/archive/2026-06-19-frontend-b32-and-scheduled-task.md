# Track #4:前端 — B32 决策效果 + job→scheduled-task 迁移 — 设计

补齐已落地后端的前端:① B32 决策效果报表页(新)② 手工回灌表单(新)③ job→scheduled-task 管理页迁移(**修当前断掉的 `/admin/v1/jobs`**)。

## 0. 约定(复用既有,照 `pages/eval-session` 范式)

- 栈:antd5 + axios(`api/client.ts`)+ zustand(`store/tenantStore`)+ react-router-dom + 强类型 i18n(`i18n/types.ts` 的 `XxxTranslation` 接口 + `locales/zh-CN|en/<ns>.ts` + 两个 `index.ts` 注册)。
- api:`api/<x>.ts` 用 `apiClient` + `ENDPOINTS`,unwrap `ApiResponse<T>`。types 加 `types/<x>.ts` + `types/index.ts` 导出。
- 路由:`constants/routes.ts` 加 `ROUTES.*` + `router.tsx` lazy + `config/menu.tsx` 菜单项。
- 端点:`constants/api-endpoints.ts` 加。
- 测试:**vitest**(`frontend` 有);新增纯函数/组件逻辑写 `*.test.ts(x)`;页面以 `tsc` 构建 + lint + 关键逻辑单测为准(无浏览器 e2e)。
- **新依赖**:`@ant-design/plots@^2`(漂移折线;antd 原生,React18 兼容)——`npm install` 一次。

## 1. B32 决策效果报表页

- 路由 `/effectiveness`,菜单「决策效果」(`BarChartOutlined`),i18n 新命名空间 `effectiveness`(zh+en + `EffectivenessTranslation` 接口入 `i18n/types.ts`)。
- `api/effectiveness.ts`:`getEffectiveness(params)` → `GET /admin/v1/decision-outcomes/effectiveness`(`ENDPOINTS.EFFECTIVENESS`),unwrap → `EffectivenessReport`。
- `types/effectiveness.ts`:对齐后端 VO ——`EffectivenessReport{ buckets: BucketReport[] }`,`BucketReport{ bucket, totalSessions, labeledCount, unlabeledCount, blockedCount, totalPositive, totalNegative, rows: EffectivenessRow[] }`,`EffectivenessRow{ dimensionKey, tp, fp, fn, tn, precision, recall, fireRate, firedTotal }`。
- 页面 `pages/effectiveness/index.tsx`:
  - **过滤条**:租户 Select(`useTenantStore`)+ 场景 Select(取 `scenes` 列表,复用 `api/scene`)+ 时间窗 antd `RangePicker`(→ from/to ISO,经 `.toISOString()`)+ positiveLabels(`Select mode="tags"` 自由输入)+ 维度 `Segmented`(RULE_VERSION/DECISION)+ 桶 `Segmented`(NONE/DAY/WEEK)+ 查询按钮。
  - **诚实横幅**:`Statistic` 行 totalSessions/labeled/unlabeled/blocked + 文案"unlabeled 不入分母、blocked = reject-inference 残缺面"。桶模式跨桶汇总。
  - **混淆矩阵表**:antd `Table`,行=维度键,列 TP/FP/FN/TN/precision/recall/fireRate/firedTotal;precision/recall 为 null 显「—」。桶模式加 bucket 列(行=桶×键)。
  - **漂移折线**(`@ant-design/plots` `Line`,仅 bucket≠NONE):x=bucket,指标 `Segmented`(precision/recall/fireRate),每维度键一条线。
- **价值门控提示**:页面顶部 Alert 注明"指标依赖业务真实标签回灌,无标签时为空/构造数据"(守 B32 门控,避免误读)。

## 2. 手工回灌表单

- 入口:决策效果页内一个「回灌标签」按钮 → antd `Modal` 表单(不单独占路由,轻量)。
- `api/effectiveness.ts` 加 `recordOutcomes(body)` → `POST /admin/v1/decision-outcomes`(`ENDPOINTS.DECISION_OUTCOMES`)。
- 表单:tenantId(取 store)+ 一行/多行 outcome(eventId + outcomeLabel + outcomeValue? + labeledAt(DatePicker)+ source? + note?)。支持「+加一行」批量。提交 → `{tenantId, outcomes:[...]}` → 成功 toast「accepted N」。
- **定位**:运维补录/演示用;不是主回灌路径(机器经 API/job)——表单简洁即可,不做复杂校验。

## 3. job → scheduled-task 管理页迁移(修断掉的前端)

后端已 `/admin/v1/jobs*` → `/admin/v1/scheduled-tasks*`、`JobDefinition`→`ScheduledTask`(taskType:String、config:JSON-Object、run_cursor)。前端同步迁移:

- **endpoints**:`ENDPOINTS.JOB_*` → `SCHEDULED_TASK_*`(`/admin/v1/scheduled-tasks` / `/{id}` / `/{id}/enable` / `/{id}/disable` / `/{id}/trigger` / `/{id}/executions`)。
- **api**:`api/job.ts` → `api/scheduledTask.ts`(list/get/enable/disable/trigger/executions);删 `api/job.ts`。
- **types**:`JobItem`→`ScheduledTaskItem{ id, tenantId, code, name, taskType:string, cron, config:unknown, status, createdAt, updatedAt }`(注:**无 create/edit 字段——本轮管理页只读+启停+触发**);`JobExecutionItem`→`ScheduledTaskExecutionItem{ id, scheduledTaskId, status, processedCount, successCount, errorCount, errorSummary, triggerAt, finishedAt }`。
- **页面**:`pages/job-list`→`pages/scheduled-task-list`、`pages/job-detail`→`pages/scheduled-task-detail`;路由 `ROUTES.JOBS/JOB_DETAIL`→`SCHEDULED_TASKS/SCHEDULED_TASK_DETAIL`(路径 `/scheduled-tasks`、`/scheduled-tasks/:taskId`);菜单 `menu.jobs`→`menu.scheduledTasks`;i18n `job` ns → `scheduledTask`(或保留 ns 名改内容,择简)。
- **前向兼容(钉死,免被 track #3 后续 RETENTION/ALARM 返工)**:
  - **task_type 通用渲染**:用 label map + **未知类型兜底**(显示原始 type 字符串),不 hardcode 仅 TRIGGER/OUTCOME_INGESTION——将来 RETENTION/ALARM 自动可显示。
  - **config 通用渲染**:按 JSON 通用展示(如 `<pre>`/键值表/折叠 JSON),**不写死** TriggerConfig/OutcomeIngestionConfig 字段形状。
  - **容忍未知字段**:VO 将来加 `scope` 等字段,前端默认忽略不崩;要显示再加列(加性)。
  - **只读管理,不做创建表单**:后端无 create API(TRIGGER 靠注解 seed);列表/详情/启停/触发/看执行记录即可。

## 4. 范围边界(YAGNI)

- 不做 scheduled-task 创建/编辑 UI(后端无 create API)。
- 不做 OUTCOME_INGESTION 任务的可视化配置(随后端 create API 再补)。
- 漂移图仅 Line(不做多图联动/导出)。
- 回灌表单不做复杂校验/CSV 导入(机器走 API)。

## 5. 落地顺序

1. **③ scheduled-task 迁移**先做(修断掉的前端,机械迁移,优先恢复一致)。
2. **① 报表页**(+ 图表库 + i18n ns)。
3. **② 回灌表单**(报表页内 Modal)。

## 6. 验证

- `npm run build`(tsc)无错 + lint 过 + 新增逻辑 vitest 绿。
- 起前端 dev(`npm run dev`)+ 已起的后端(local profile)手测:scheduled-task 列表/触发、effectiveness 查询(可空数据)、回灌提交 → 落库(经 MCP 核对 decision_outcome)。无标签时报表空属正常(门控)。
- 文档:`docs/06-frontend.md` 补「决策效果页」「调度任务管理(原 job)」两节。
