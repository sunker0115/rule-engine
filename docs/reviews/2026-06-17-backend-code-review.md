# 后端 Code Review 问题清单（2026-06-17）

来源：`/code-review 后端全部代码`，5 个 finder 角度（kernel / eval-svc / config-svc / api+sdk+audit+job / 横切并发事务资源）+ 单票验证。
处理方式：**逐条沟通确认后再修复**。每条带「状态」「待拍板点」。状态取值：`待沟通` / `已确认方案` / `修复中` / `已修` / `不修(说明原因)`。

按严重度分组（P0 最高）。

---

## P0 — 会导致错误业务决策 / 已禁用规则仍在线

### #1 EvalEngine FIRST_HIT 吞 ERROR + 吞执行器异常
- **位置**：`rule-kernel/.../internal/engine/EvalEngine.java:192,202`（`evaluateFirstHit`）
- **根因**：只看 `r.ruleHit()`，ERROR 候选（ruleHit=false + errorCode 非空）被跳过；`catch (Exception ignored)` 吞掉执行器异常，都不收集 errorCode。对比 `evaluateAllCandidates`(223/230) 显式传播——两条策略路径三态处理不对称。
- **失败场景**：FIRST_HIT 下高优先级规则取数失败（ERROR）被跳过 → 命中低优先级规则触发**错误决策**；或全跳过返回 `miss()` 且 errorCode=null，调用方无法区分"无命中"与"降级失败"。
- **改法**：循环收集首个非空 errorCode，catch 设 `CONDITION_EVAL_ERROR.name()`（不再 ignored）；无命中时返回带 errorCode 的 EvalResult 而非裸 `miss()`。
- **待拍板**：高优先级 ERROR 时 FIRST_HIT 应**继续试低优先级**（仅带出 errorCode）还是**直接返回 ERROR 不下试**（避免静默降级命中错误决策）？需对齐 D15。倾向后者更安全。
- **状态**：待沟通

### #2 disable 规则运行时不下线
- **位置**：`rule-config-svc/.../internal/service/ConfigServiceImpl.java:59,83`（`transitionStatus`）+ `RuleVersionReadMapper.loadActiveByScene`
- **根因**：disable/enable 只 `UPDATE rd.status` + 发 `OperationAuditedEvent`（审计），**不发索引失效事件**；索引 loader 只过滤 `rv.status='ACTIVE'`，不看 `rd.status`。DISABLED 规则的 rv 仍 ACTIVE 且索引不刷新。
- **失败场景**：disable 一条线上规则 → 审计 DISABLED，但 eval-svc 仍评估它、仍出决策，直到该 scene 偶发其它 publish 才刷索引。已禁用规则实质无法运行时下线。
- **改法**：① loader join `rule_definition` 加 `rd.status='PUBLISHED'` 过滤；② disable/enable 发索引失效事件（复用 `SceneChangedEvent` 或新增 `RuleStatusChangedEvent`）触发重建。
- **修前必须确认**：`MetricWriteServiceImpl` 有注释称"故意不按 rd.status 过滤"——先 grep 是否有监听 rule 状态变更的索引摘除 listener，确认是真 gap 还是有我们没看到的机制。**本条最该优先确认。**
- **状态**：待沟通（先确认设计意图）

---

## P1 — 数据丢失 / 脏数据落库

### #3 AuditPersister 单条坏数据丢整批
- **位置**：`rule-eval-svc/.../internal/async/AuditPersister.java:132`（`toSession`）+ `flushBatch:95`
- **根因**：`toSession` 的 `writeValueAsString` 裸调用无守卫，单条序列化失败抛进 `.map()` 流，被 `catch (RuntimeException ignored)` 整批（≤500）吞掉。
- **改法**：`toSession` 内所有 `writeValueAsString` 改走已有 `serializeJson`(157) best-effort 包装；`flushBatch` catch 加 `log.warn`（不再静默）。
- **状态**：待沟通

### #4 停机丢未消费事件/审计
- **位置**：`rule-eval-svc/.../internal/dispatch/PushEventDispatcher.java:61`（`stop`）+ `AuditPersister.java:170`（`destroy`）
- **根因**：`running=false` 后**立即 interrupt** 消费线程，正阻塞在 `poll`/`sleep` 的线程抛 InterruptedException 直接 break，队列剩余不排空。
- **失败场景**：滚动发布/优雅停机时积压的 PUSH 事件永不评估、审计积压（>500）丢失。
- **改法**：`running=false` → 消费循环排空队列（`while (!queue.isEmpty()) drain/flush`）→ `join(超时)` 等自然退出 → 仅超时才 interrupt + 最后 best-effort drain。两处同构一并改。
- **状态**：待沟通

### #5 durationMs 用 evalNow(asOf) 产生脏时长
- **位置**：`rule-eval-svc/.../internal/service/EvalServiceImpl.java:145`
- **根因**：`durationMs = Duration.between(evalNow, now)`，`evalNow = asOf ?? now`；asOf/replay 时是历史时刻 → 时长变成历史到墙钟间隔，`(int)` 毫秒 ~24.8 天后溢出，`finishedAt` 连带错。
- **改法**：方法入口单独捕获 `Instant startWall = Instant.now()` 测耗时；evalNow 只用于评估逻辑。一行改动。
- **状态**：待沟通

---

## P2 — 正确性 / 资源 / 并发

### #6 MatchesEvaluator null→"null" 误命中
- **位置**：`rule-kernel/.../internal/condition/MatchesEvaluator.java:37`
- **根因**：`String.valueOf(mv.value())`，value=null 时产出字面量 `"null"` 去匹配正则。
- **失败场景**：`field MATCHES "nul.*"`（或 `.*`）对实际 null 的字段误判 TRUE。ConditionEvaluation 只拦 `isError()` 不拦 `value==null`。
- **改法**：matcher 前判 `value==null → false`；**全仓 grep 平行字符串算子**（Contains/StartsWith/EndsWith 等）是否同款 `String.valueOf(null)` 孪生 bug，一并修。
- **状态**：待沟通

### #7 DeclarativeHttpConnectorHandler HttpClient 泄漏
- **位置**：`rule-eval-svc/.../internal/metric/fetch/DeclarativeHttpConnectorHandler.java:105`
- **根因**：每次 fetch `new HttpClient`，从不关闭（JDK21 起持有 selector 线程 + 连接池）。
- **失败场景**：高 QPS EXTERNAL_HTTP 取数泄漏线程/fd，且每次重做 connect/TLS 失去连接池复用。
- **改法**：复用共享 HttpClient（线程安全，可单例）；read timeout 走 `HttpRequest.timeout()`（per-request），connectTimeout 用 client 级默认。
- **状态**：待沟通

### #8 MetricDefinitionRegistry.replaceAll 非原子
- **位置**：`rule-sdk/.../metric/MetricDefinitionRegistry.java:49`
- **根因**：`removeIf(前缀)` + `for put` 两段操作非原子，热更窗口内评估线程 `get()` 读到"旧删新未写"空洞 → 指标瞬时缺失 → METRIC_FETCH_FAIL 误判。
- **改法**：内部 `volatile Map` 引用 + copy-on-write——构建新完整 map 后原子换引用，读永远看到一致快照。
- **状态**：待沟通

### #9 RuleImportService 重复 DRAFT + scriptSource 丢失
- **位置**：`rule-config-svc/.../internal/bundle/RuleImportService.java:182` + `RuleBundle.java`(RuleEntry) + `RuleExportService.java:108`
- **根因（两个独立 bug）**：
  - import 到已有规则无条件追加 DRAFT，破坏 `newVersion` 强制的"同时只一条 DRAFT"不变式 → 产生孤儿 DRAFT。
  - `RuleEntry` 无 script 字段，export/import 全程不映射 `scriptSource` → EXPRESSION_SCRIPT 规则 round-trip 后 script=NULL（数据丢失），且 import 不跑 `resolveAndValidate` 不报错。
- **改法**：① import 前复用"已有 DRAFT 则拒/覆盖"校验；② `RuleEntry` 加 scriptSource，export 填充、import 映射 + 走 resolveAndValidate。
- **状态**：待沟通

---

## P3 — robustness / 入参校验

### #10 tenantId 非数字 → 500
- **位置**：`rule-api/.../web/admin/MetricController.java:67` 等多 controller + `rule-audit-svc/.../AuditServiceImpl.java:31` 等
- **根因**：多处 `Long.parseLong(tenantId)` 裸调用，NumberFormatException 落到通用 500。
- **已定方案：A（写链路统一 Long，评估 SPI 保持 String）**

#### 核心 tenantId 类型分层（真相）
- **持久层 / domain entity = `Long`**：13 个 entity 全是 `private Long tenantId`，DB BIGINT，**已完全一致，是真相源**。
- **评估运行时 SPI（kernel）= `String`**：`RuleEvent` / `EvalContext` / `RuleVersionSnapshot` / `PreGateContext` / `MetricQuery` / `SceneRuleIndex` 倒排索引键——不透明租户标识（纯 SPI + SDK 本地模式灵活）。**保持 String，不动**（动它破坏 SDK 公开契约 + 索引键 + 评估事件契约）。
- **admin/config 写链路 service = 统一成 `Long`**：消除现状 String/Long 混用（ConfigService 自己都混用）。

#### 边界转换点清单
**【保留】DB Long ⇄ 评估 SPI String（合理边界，~15 处）**
- 出（DB→评估）：`SnapshotAssembler:60`、`SceneSnapshotLoader:100/101`、`ReplayServiceImpl:64`、`JobRunner:67`、`MetricFetchTester:65/71/98`
- 入（评估→DB）：`SceneSnapshotLoader:71/87`、`EvalServiceImpl:92(parseTenantId)`、`AuditPersister:114`、`ConnectorIndexEventListener:25`、`DbMetricDefinitionResolver:42`、`DeclarativeHttpConnectorHandler:347`
- 评估 API 入口：`EvalController:96`、`SceneManifestController:42`

**【消除】写链路 String→Long（统一 service Long 后删，~30 处）**
- controller 裸 parse（会 500 的元凶）：`ConnectorController:67/85/104/121/137`、`MetricController:67/86/101/138`
- service 内部 `Long.valueOf`（接口收 String 导致）：`ConfigServiceImpl`(9)、`SceneServiceImpl`(11)、`MetadataServiceImpl`(7)、`AuditServiceImpl`(5)、`JobServiceImpl`(2)、`RuleImport/ExportService`(各 1)

**【简化】equals 比较（3 处）**
- `ConfigServiceImpl:86/136/174` `tenantId.equals(String.valueOf(rule.getTenantId()))` → `tenantId.equals(rule.getTenantId())`

#### 落地范围
1. 补 `MethodArgumentTypeMismatchException → 400` handler（立即 500→400，覆盖所有 typed 参数含 `@PathVariable Long id`）；
2. 9 个 controller `String tenantId` → `@RequestParam Long tenantId`，删裸 parse；
3. 7 个 service 接口（Audit/Scene/Replay/Metadata/RuleBundle/Job + ConfigService 的 String 部分）`String→Long` + 实现内删 `Long.valueOf`；
4. 3 处 equals 简化。
5. **评估侧 kernel/eval-svc 一律不碰**；评估 API 仍走 tenantCode→id resolve（独立维度）；`EvalServiceImpl.parseTenantId` 保留（评估 String→Long 边界）。
- **状态**：方案 A 已确认 + 边界已标，待落地

### 备注项（验证存在，未进 top10）
- **publish/newVersion 不检查 rule.status**：`PublishService` —— DISABLED 规则若有 DRAFT 可经 publish 翻 PUBLISHED，绕过 `transitionStatus` 状态机（审计记 PUBLISH 而非 ENABLE）。改法：publish 入口加 status 校验。
- **EvalController 缺 @Valid**：`/api/v1/rule/evaluate` 收只含 tenantCode 的 body，null sceneCode/eventId 流入引擎/审计。改法：`@Valid` + `EvalEventRequest` 字段加 `@NotBlank`。
- **RuleEngineClient listener 不吞异常**：`evalResultListener`/`evalSessionListener` 调用未包 try/catch，host 回调抛异常中断 `evaluate()`。改法：包 try/catch(吞+log)，对齐 `decisionContextListener`。
- **publish 并发竞态**：两并发 publish 同规则，`markSuperseded` 可能漏掉对方刚激活版本，留 >1 ACTIVE（无行锁/乐观版本）。改法：`rule_definition` 行锁或乐观版本号。
- **ObservabilityAlarmChecker 用进程累计计数器算 error rate**：warm-up 后告警失效。改法：改滑动窗口/速率。

---

## 处理顺序建议
P0（#1 #2）先确认设计意图再修 → P1（#3 #4 #5 数据丢失/脏数据）→ P2 → P3。每条修复配复现/回归测试。
