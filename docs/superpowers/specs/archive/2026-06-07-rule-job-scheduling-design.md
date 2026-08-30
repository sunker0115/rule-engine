# rule-job 调度扩展与边界 设计

> Spec。本文为**决策捕获 + 接入设计**:xxl-job 接入延后到真正构建时落地,REST 触发入口可即时落,其余为边界结论。实现计划见后续 `docs/superpowers/plans/`。

## 背景

`rule-job-svc` 现状:`@RuleJob` 声明 job → 启动期落库 → `Scheduler` SPI 注册 cron → 触发时 `JobRunner.run(def)` 查主体 → 合成 `RuleEvent(source=JOB)` → `EvalService.acceptEvent` 异步注入 → 记 `JobExecution`。进程内调度器 `ThreadPoolSchedulerAdapter` 是**单实例**语义(多实例部署会重复触发,docs §3.10 已记)。

诉求起点是"要上生产 / 多实例 → 接 xxl-job 复杂么";讨论中扩散到"要不要把 job 组装提成 SDK / 通用 job 平台 / 把 rule 业务搬进业务进程"等一连串问题。本文记录收敛后的边界。

## 核心结论(经讨论收敛)

1. **job 是服务端生产侧概念,不做嵌入式 job SDK**。理由见下节两条评估路径对比。
2. **调度是唯一的"通用"接缝 = 现有 `Scheduler` SPI,保持不变**(含 `Runnable` 形参)。不瘦身、不新增 `JobRunService`——现有 `JobScheduleManager.runById`(带 ACTIVE 守卫)+ `JobService.triggerOnce`(手动)已覆盖"守卫触发 / 手动触发"两种语义。
3. **xxl-job 作为 `Scheduler` 的另一实现接入**,复用 `JobRunner`,**不重写组装**。装配钩子已在 `JobAutoConfiguration` 预留。
4. **跨服务 / 通用定时触发 → 瘦 REST 触发入口**,架在已有 `JobService.triggerOnce` 上,不发 SDK 组装包。
5. **`source=JOB` 保留**(审计/运营维度,D49 已落地)。
6. **不做**:通用 job 平台、`JobDefinition` 去 rule 化、`Scheduler` SPI 瘦身、嵌入式 job SDK、`RuleJobTemplate`/`EventSink` 提取(均为 YAGNI,讨论中提出后否决)。

## 两条评估路径对比(为何不做嵌入式 job SDK)

| | SDK(`RuleEngineClient`) | rule-job-svc(`EvalService`) |
|---|---|---|
| 引擎在哪 | 嵌在**业务进程**(本地 `EvalEngine`) | 在**引擎服务进程**(rule-app) |
| 走哪层 | 只走 **kernel** | 走完整 **eval-svc** 服务层 |
| 同步/异步 | 同步,直接返回 `EvalResult` | 异步 PUSH 入队,返回 boolean |
| session 落库 | 不落,靠 listener 回调 | 落 `evaluation_session`(source/mode) |
| 背压 | 无(同步) | 有(队列满重试) |
| source | SDK | JOB |

**"job"机制 = 定时触发 + 主体来源在别处 + 异步扇出带背压 + 运行审计(`JobExecution`),四样全在服务端生产侧。** 业务用 SDK 时引擎已在其进程内,`evaluate()` 就在手边,要批量自己 `for` 循环即可,四样机制全失效——此时 "job" 退化为普通循环,不值得抽象。故 job 留服务端,不做嵌入式 job SDK。

## 现状接缝:已为 xxl 预留

- `Scheduler`(rule-kernel SPI):`schedule(jobCode, cron, Runnable)` / `unschedule(jobCode)`。唯一消费者是 job 模块(`JobScheduleManager`)。
- `JobAutoConfiguration.scheduler()`:`@ConditionalOnProperty(prefix="engine.rule.job", name="scheduler", havingValue="in-process", matchIfMissing=true)` + `@ConditionalOnMissingBean(Scheduler.class)`。
  - 设 `engine.rule.job.scheduler=xxl-job` → 进程内 Bean 不装配;
  - xxl 模块提供的 `Scheduler` Bean 经 `@ConditionalOnMissingBean` 接管。
- **结论:接 xxl,内制侧与业务侧(`JobDefinition`/`JobExecution`/`JobRunner`)零改动。**

## xxl-job 接入设计(future 模块 `rule-job-xxl`)

> 模块命名遵循约定:`-svc` = 限界上下文业务模块;`rule-<context>-<impl>` = 适配器/插件。xxl 是 `Scheduler` 的一个适配实现,故 `rule-job-xxl`(非 `-svc`)。平铺为根 pom 第 13 个模块;按需引入,不引则不影响。

### 依赖与版本

- `com.xuxueli:xxl-job-core:3.4.0`(**稳定版**,2026-04-05;`3.4.1-SNAPSHOT` 为未发布快照,不用)。
- `xxl-job-core` 自身较轻(不拖入 Spring Boot);Spring Boot 4 的兼容性关切主要在 admin 端(独立部署,非本模块)。

### `XxlJobSchedulerAdapter implements Scheduler`

- `schedule(jobCode, cron, task)`:
  - 把 `task`(即 `() -> runById(jobId)` 闭包)注册为一个 `IJobHandler`,handler 名 = `jobCode`;
  - 启动期把该 job **seed 到 admin**(executorHandler=jobCode、cron、appname 等)。
- `unschedule(jobCode)`:停用/移除对应 handler 注册(admin 侧策略见下)。
- admin 远程触发该 handler → 执行 `task` → 复用 `JobRunner` 整套(查主体/背压/审计),与内制完全一条路。

### 注册语义:**有了不管**(与参考实现相反)

- 旧参考实现的 `JobInfoServiceImpl.addJobInfo` 在发现已存在 handler 时调用 `updateJobInfo` **覆盖 admin 配置**——即"有了就 update"。
- **本设计取反**:启动 seed 时若 admin 已存在该 handler,**保持不动**(不 update),让 **admin 控制台成为 cron 的权威源**,运维改了不被启动覆盖。
- 不可在 admin 改动的身份字段:`executorHandler` / `executorParam` / `appname`(改了会与执行器对不上)。

### 双开关(app 与 admin 各自能停)

- **app 侧禁用**:`JobService.disableJob` → `status=DISABLED`;`runById` 已有 `"ACTIVE".equals(status)` 守卫,即使 admin 遗留触发也不执行。
- **admin 侧停止**:控制台 stop 对应 job。
- 两者 AND 语义:任一侧停,即不跑。

### 鉴权与配置(敏感)

- admin 地址 / `accessToken` / `username` / `password` 为**敏感配置**,按 secret 处理(环境变量 / 配置中心),不入库、不进代码。
- 登录态:参考实现缓存 `XXL_JOB_LOGIN_IDENTITY` cookie、失效重登(3 次)——可复用思路,HTTP 客户端按本项目约定用 JDK `java.net.http.HttpClient` + 注入的 `ObjectMapper` Bean(不引 hutool)。

### GraalVM Native Image(硬约束)

- 接 xxl 前**必须验证** `xxl-job-core` + Netty(执行器回调端口)+ 其依赖(Groovy 等)的 reachability hints 在 native image 下可用,缺则补 `reachability-metadata` 或 hint。这是 go/no-go 前置项,不是收尾项。

## 对外 REST 触发入口(瘦,可即时落)

- 在 rule-api 暴露:`POST /jobs/{jobId}/trigger`(租户经现有鉴权上下文)→ 调 `JobService.triggerOnce(tenantId, jobId)`。
- 用途:跨服务主动触发一次 job;或"通用定时任务"(任意外部调度)回调它触发。
- 不新建 service:`triggerOnce` 已是"手动触发一次、不经调度器"的入口,REST 仅做暴露 + 鉴权/租户校验。
- 不发 SDK 组装包:跨服务要触发就调这个 REST,而非把 job 组装搬进调用方进程。

## 明确不做(边界)

| 不做 | 原因 |
|---|---|
| 嵌入式 job SDK / `RuleJobTemplate` / `EventSink` 提取 | 嵌入式场景 job 退化为循环,无第二消费者(YAGNI) |
| 通用 job 服务 / 平台 | rule 是唯一消费者,通用只到调度 seam 为止 |
| `JobDefinition` 去 rule 化 | 为不存在的通用消费者上抽象 |
| `Scheduler` SPI 瘦身(去 `Runnable`)/ 新增 `JobRunService` | 现有 `Runnable` 闭包对 xxl 同样可用,`runById`+`triggerOnce` 已覆盖语义,改动无收益 |

## 测试策略

- **REST 触发入口**(rule-api):`POST /jobs/{id}/trigger` 命中 `triggerOnce`,租户/鉴权校验;非法 jobId / 跨租户拒绝。
- **xxl 接入(构建时)**:`XxlJobSchedulerAdapter` 单测——`schedule` 注册 handler 名=jobCode、seed "有了不管"(已存在不 update);`unschedule` 撤销;admin 触发回调走通 `JobRunner`(以 stub `EvalService` 验证注入计数)。native image smoke(执行器启动 + 回调端口)。
- **回归**:内制路径(`engine.rule.job.scheduler` 默认/`in-process`)装配与触发不受影响。

## 决策记录(待写入 00-decisions)

- **D50**:rule-job 调度扩展边界——job 为服务端生产侧概念,**不做嵌入式 job SDK**(嵌入式 `RuleEngineClient` 已本地持有引擎,批量即循环 `evaluate`);xxl-job 作为 `Scheduler` 适配实现接入(future `rule-job-xxl`,`xxl-job-core:3.4.0`),复用 `JobRunner` 不重写组装,装配经已预留的 `engine.rule.job.scheduler=xxl-job` 钩子;注册语义"**有了不管**"(admin 为 cron 权威源,与旧参考实现的"有了就 update"相反);双开关(app `status` 守卫 + admin stop);`source=JOB` 保留(审计维度);跨服务触发经 rule-api 瘦 REST 入口(`triggerOnce`),不发组装 SDK。明确不做:通用 job 平台 / `JobDefinition` 去 rule 化 / `Scheduler` SPI 瘦身。GraalVM native image 兼容性为接 xxl 的 go/no-go 前置。

## 实施后批注(2026-06-08)

- **实现已落地**:`rule-job-xxl` 模块已实现并提交(commit `6438b24`→`629df63`);JVM 模式执行器完整可用(EmbedServer 绑端口 + admin 注册)。
- **xxl native 运行期 = NO-GO(本文上文"native image smoke(执行器启动)"的预期未达成)**:实测 `XxlJobSpringExecutor` 的 `SmartInitializingSingleton.afterSingletonsInstantiated()` 回调在 AOT/native 下**不触发**,执行器不启动——这是架构层限制,**非补 reachability hint 能解**。镜像构建与 Spring 装配能过,仅执行器运行期死。
- **决定**:xxl 仅 JVM;native 部署走 in-process 调度器。详见 memory `project_xxl_native_deferred`(含将来改造方向:`ApplicationReadyEvent` 显式驱动 executor.start)。
