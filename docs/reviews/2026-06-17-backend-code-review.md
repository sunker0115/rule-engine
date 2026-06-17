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
- **已定**：高优先级 ERROR/异常 → **直接返回 ERROR，不下试低优先级**（避免静默降级命中错误决策）。
- **实际落地**：`evaluateFirstHit` 改为——执行器抛异常 → 返回 `CONDITION_EVAL_ERROR`；`r.errorCode()!=null` → 返回该 errorCode；仅真正 miss（无 error）才继续试下一个。EvalEngineTest 加 2 用例（ERROR 候选 / 执行器抛异常）验证不降级命中。
- **状态**：已修

### #2 disable 规则运行时不下线
- **位置**：`rule-config-svc/.../internal/service/ConfigServiceImpl.java:59,83`（`transitionStatus`）+ `RuleVersionReadMapper.loadActiveByScene`
- **根因**：disable/enable 只 `UPDATE rd.status` + 发 `OperationAuditedEvent`（审计），**不发索引失效事件**；索引 loader 只过滤 `rv.status='ACTIVE'`，不看 `rd.status`。DISABLED 规则的 rv 仍 ACTIVE 且索引不刷新。
- **失败场景**：disable 一条线上规则 → 审计 DISABLED，但 eval-svc 仍评估它、仍出决策，直到该 scene 偶发其它 publish 才刷索引。已禁用规则实质无法运行时下线。
- **改法**：① loader join `rule_definition` 加 `rd.status='PUBLISHED'` 过滤；② disable/enable 发索引失效事件（复用 `SceneChangedEvent` 或新增 `RuleStatusChangedEvent`）触发重建。
- **已确认为真 gap**：无任何监听 rule 状态变更的索引摘除 listener；`MetricWriteServiceImpl` 那条注释只是说明"eval 与影响面判断口径一致地都漏了 rd.status"，非保护性设计。
- **实际落地**：
  - ① `RuleVersionReadMapper.loadAllActive/loadActiveByScene` 两处 SQL 加 `AND rd.status='PUBLISHED'`——DISABLED 规则不进倒排索引。
  - ② `ConfigServiceImpl.transitionStatus`（disable/enable）发 `RulePublishedEvent` 触发该 scene 索引重建（复用现有 listener，自动覆盖索引重载 + 编译缓存清除）。
  - ③ 端到端暴露：`onRulePublished` 逐桶 `update` 在空结果时不清旧桶（disable 掉 scene 唯一规则后残留）→ 新增 `SceneRuleIndex.replaceScene`（先写新桶、再删该 scene 已不存在的旧桶，空结果也摘除），`RuleIndexEventListener`/`SceneIndexEventListener` 改用它——**顺带根治备注项 finder #8 的 torn-index**。
  - `MetricWriteServiceImpl.findActiveByRuleDefIds`（影响面判断）保留不过滤 rd.status（保守多算 DISABLED），注释已更新说明。
- **验证**：全量 clean test 绿；端到端 disable→ruleHit=False、enable→恢复命中。
- **状态**：已修

---

## P1 — 数据丢失 / 脏数据落库

### #3 AuditPersister 单条坏数据丢整批
- **位置**：`rule-eval-svc/.../internal/async/AuditPersister.java:132`（`toSession`）+ `flushBatch:95`
- **根因**：`toSession` 的 `writeValueAsString` 裸调用无守卫，单条序列化失败抛进 `.map()` 流，被 `catch (RuntimeException ignored)` 整批（≤500）吞掉。
- **改法**：`toSession` 的 hit_decisions 改走 `serializeJson` best-effort 包装（失败降级 null，不抛进 stream）；`flushBatch` 两处 catch 加 `log.warn`。
- **状态**：已修

### #4 停机丢未消费事件/审计
- **位置**：`rule-eval-svc/.../internal/dispatch/PushEventDispatcher.java:61`（`stop`）+ `AuditPersister.java:170`（`destroy`）
- **根因**：`running=false` 后**立即 interrupt** 消费线程，正阻塞在 `poll`/`sleep` 的线程抛 InterruptedException 直接 break，队列剩余不排空。
- **失败场景**：滚动发布/优雅停机时积压的 PUSH 事件永不评估、审计积压（>500）丢失。
- **改法**：`AuditPersister.destroy` 改为 `while(!queue.isEmpty()) flushBatch()` 排空整个队列再 join；`PushEventDispatcher.stop` 改为 `running=false` + `join(10s)` 等消费循环排空再退，超时才 interrupt 兜底。
- **状态**：已修

### #5 durationMs 用 evalNow(asOf) 产生脏时长
- **位置**：`rule-eval-svc/.../internal/service/EvalServiceImpl.java:145`
- **根因**：`durationMs = Duration.between(evalNow, now)`，`evalNow = asOf ?? now`；asOf/replay 时是历史时刻 → 时长变成历史到墙钟间隔，`(int)` 毫秒 ~24.8 天后溢出，`finishedAt` 连带错。
- **改法**：方法入口捕获 `Instant startWall = Instant.now()` 测耗时；evalNow=asOf?startWall 只用于评估逻辑。`durationMs` 用 startWall→now。
- **状态**：已修

---

## P2 — 正确性 / 资源 / 并发

### #6 MatchesEvaluator null→"null" 误命中
- **位置**：`rule-kernel/.../internal/condition/MatchesEvaluator.java:37`
- **根因**：`String.valueOf(mv.value())`，value=null 时产出字面量 `"null"` 去匹配正则。
- **失败场景**：`field MATCHES "nul.*"`（或 `.*`）对实际 null 的字段误判 TRUE。ConditionEvaluation 只拦 `isError()` 不拦 `value==null`。
- **改法**：matcher 前判 `value==null → false`；**全仓 grep 平行字符串算子**（Contains/StartsWith/EndsWith 等）是否同款 `String.valueOf(null)` 孪生 bug，一并修。
- **实际落地**：`MatchesEvaluator:33` 加 `mv==null || mv.value()==null → false`；孪生算子核实——Contains（`instanceof Collection` 守卫）/ StartsWith:21 / EndsWith:22 均已带 null 守卫，无孪生 bug。
- **状态**：已修（核验 2026-06-17）

### #7 DeclarativeHttpConnectorHandler HttpClient 泄漏
- **位置**：`rule-eval-svc/.../internal/metric/fetch/DeclarativeHttpConnectorHandler.java:105`
- **根因**：每次 fetch `new HttpClient`，从不关闭（JDK21 起持有 selector 线程 + 连接池）。
- **失败场景**：高 QPS EXTERNAL_HTTP 取数泄漏线程/fd，且每次重做 connect/TLS 失去连接池复用。
- **改法**：复用共享 HttpClient（线程安全，可单例）；read timeout 走 `HttpRequest.timeout()`（per-request），connectTimeout 用 client 级默认。
- **实际落地**：`DeclarativeHttpConnectorHandler:68/95/129` 改共享 `sharedHttpClient`（构造器 @Nullable 注入，缺省 `connectTimeout 5s`），read timeout 走 `HttpRequest.timeout()`。
- **状态**：已修（核验 2026-06-17）

### #8 MetricDefinitionRegistry.replaceAll 非原子
- **位置**：`rule-sdk/.../metric/MetricDefinitionRegistry.java:49`
- **根因**：`removeIf(前缀)` + `for put` 两段操作非原子，热更窗口内评估线程 `get()` 读到"旧删新未写"空洞 → 指标瞬时缺失 → METRIC_FETCH_FAIL 误判。
- **改法**：内部 `volatile Map` 引用 + copy-on-write——构建新完整 map 后原子换引用，读永远看到一致快照。
- **实际落地**：`MetricDefinitionRegistry` 字段改 `AtomicReference<Map>`（初值 `Map.of()`）；`put`/`replaceAll` 用 `updateAndGet` 在 `HashMap` 副本上改完后 `Map.copyOf` **无锁 CAS** 原子换引用（不加 synchronized）；`get` 零锁读快照。新增并发回归测试 `replaceAll_concurrentGet_neverSeesTornMissingEntry`（5000 轮并发 replaceAll × get，断言始终命中）；全量 `clean test` 27 模块绿。
- **状态**：已修（2026-06-17）

### #9 RuleImportService 重复 DRAFT + scriptSource 丢失
- **位置**：`rule-config-svc/.../internal/bundle/RuleImportService.java:182` + `RuleBundle.java`(RuleEntry) + `RuleExportService.java:108`
- **根因（两个独立 bug）**：
  - import 到已有规则无条件追加 DRAFT，破坏 `newVersion` 强制的"同时只一条 DRAFT"不变式 → 产生孤儿 DRAFT。
  - `RuleEntry` 无 script 字段，export/import 全程不映射 `scriptSource` → EXPRESSION_SCRIPT 规则 round-trip 后 script=NULL（数据丢失），且 import 不跑 `resolveAndValidate` 不报错。
- **改法**：① import 前复用"已有 DRAFT 则拒/覆盖"校验；② `RuleEntry` 加 scriptSource，export 填充、import 映射 + 走 resolveAndValidate。
- **实际落地**：① `RuleImportService:186/191` 已有规则改"有 DRAFT 则 editDraft，否则基于 ACTIVE 建 newVersion"，全走 createDraft/editDraft/newVersion（内含 resolveAndValidate）；② `RuleBundle.RuleEntry:58` 加 `script` 字段（+ contentHash 幂等），`RuleExportService:115/124` 填充、import 映射。
- **状态**：已修（核验 2026-06-17）

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
- **实际落地**：`GlobalExceptionHandler:42` 加 `MethodArgumentTypeMismatchException → 400` handler；controller（MetricController / ConnectorController 等）`tenantId` 全改 `@RequestParam Long`，删裸 parse；config-svc / audit-svc service 层无 `Long.valueOf(tenantId)` 残留。
- **状态**：已修（核验 2026-06-17）

### 备注项（验证存在，未进 top10）
- ✅ **publish/newVersion 不检查 rule.status**（已修，89935100）：`PublishService.publish` 入口加 DISABLED 校验。
- ✅ **EvalController 缺 @Valid**（已修，89935100）：`@Valid` + `EvalEventRequest` 字段 `@NotBlank`。
- ✅ **RuleEngineClient listener 不吞异常**（已修，89935100）：三个 listener 调用包 try/catch(吞+warn)。
- ⏸ **publish 并发竞态**（已评估，暂不修）：两并发 publish 同规则不同 draft 可能留 >1 ACTIVE。场景罕见（需 A publish + B newVersion 后 publish 精确交错）+ admin 低频。用户决定：不用 DB 悲观锁；乐观锁 `@Version` 投入产出不划算，暂跳过。
- ✅ **ObservabilityAlarmChecker 累计计数器算 error rate**（已修）：改增量滑动窗口——`checkErrorRate` 用自上次 check 的增量（deltaErrors/deltaTotal）算本周期错误率，不再被历史累计稀释/拉高。配 2 个特性测试（第二健康窗口不误报 / 第二高错窗口再告警）。

---

## 处理顺序建议
P0（#1 #2）先确认设计意图再修 → P1（#3 #4 #5 数据丢失/脏数据）→ P2 → P3。每条修复配复现/回归测试。
