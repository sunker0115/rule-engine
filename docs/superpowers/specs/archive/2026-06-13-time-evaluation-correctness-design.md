# 时间求值正确性设计(scene 默认时区接入 + 可传求值时钟)

> 日期:2026-06-13。来源:D69 后续①。两件同区域的事合并:① 让 `scene.default_params`(timezone 等 ambient 配置)真正到达评估,填上 `TimeZoneResolver` 恒 null 的 `sceneDefaultTimezone` 槽;② 把求值时刻 `now` 从服务入口写死的 `Instant.now()` 改为**调用方可传**(asOf),解决不可复现 / 只能处理时刻 / 难测。
>
> 对标:Drools KIE `globals`(共享 ambient 配置袋)+ `SessionClock`(可切时钟);Camunda DMN/FEEL `EvaluationContext` + 引擎级默认时区(优先序 显式>引擎配置);OPA `time.clock([ns,tz])` 显式参数 + `input.now` 注入(确定性)。

## 1. 目标与非目标

**目标**:
- scene 级 ambient 配置(当前仅 timezone 有消费者)经统一**通道**到达评估;时间类条件在条件未显式声明 timezone 时,按 scene 默认时区判定(优先序 `条件 params.timezone > scene 默认 > UTC`)。
- 求值时刻可由调用方传入(asOf),缺省 `Instant.now()`;支持可复现回放 / 事件时刻语义 / 可测。
- 全程无魔法串:default_params 键走 `SceneDefaultParams` 常量。

**非目标**:
- 不为 currency 等**尚无消费者**的键写消费逻辑(通道通用,消费者按需;YAGNI)。
- 不引入引擎全局默认时区层(`条件>scene>UTC` 足够,全局层留待真需要)。
- 不做 pseudo-clock 框架化(asOf 是单次传入,不做 Drools 式可推进时钟对象)。

**成功判据**:
1. scene 配 `default_params.timezone=Asia/Shanghai` + time.window 规则,条件不带 timezone → 按上海时区判时段(改 scene 时区立即生效,live)。
2. 同一事件传固定 asOf 评估 → 结果可复现(回放/测试确定)。
3. scene 创建/更新配非法 timezone → HTTP 400(authoring 期 fail-fast)。
4. 不传 asOf → 行为与今天一致(`Instant.now()`)。

## 2. 现状与缺口

- `EvalEngine.evaluateWithContext(event, candidates, now)`:`now` 已是**显式入参**,"整棵 AST 共用一个评估时刻"(设计良好,不改)。
- 缺口①:`EvalServiceImpl` 服务入口写死 `Instant evalNow = Instant.now()`,HTTP API 无传时间的口子 → 处理时刻、不可复现。
- 缺口②:`TimeZoneResolver.resolve(paramsTimezone, sceneDefaultTimezone)` 的 7 个调用点(Eq/Neq/Between/NotBetween/DateComparisonSupport/TimeWindow/OccurredAt)`sceneDefaultTimezone` 恒传 `null`;`scene.default_params` 根本没加载进评估(`SceneSnapshotLoader` 只取 `decision_strategy` 进 `SceneRuleIndex`)。
- `EvalContext` 字段:`(tenantId, event, subject, metrics, now)`,无 ambient 配置槽。
- 既有两个时刻:`event.occurredAt`(业务事件时刻,调用方传)、`now`(处理时刻,服务器生成)。

## 3. 设计 —— A. Scene default_params 通道 + timezone 消费

### A1. SceneDefaultParams 常量(无魔法串)
- 新增 kernel `api/model/SceneDefaultParams`:`public static final String TIMEZONE = "timezone";`(封闭真相源,currency 等将来扩此)。
- 与 `ConditionParams.TIMEZONE` 同字面但分属"scene 配置键 / 条件 param 键"两命名空间,各自常量、各自测试 pin。

### A2. 通道:scene default_params → SceneRuleIndex → EvalContext(live)
- `SceneRuleIndex`(kernel)新增 `Map<String,Map<String,Object>> defaultParams` + `setDefaultParams(tenantId, sceneCode, map)` / `getDefaultParams(tenantId, sceneCode)`(仿现有 `setStrategy/getStrategy`,key=`tenantId:sceneCode`,缺省返回 `Map.of()`)。
- `SceneSnapshotLoader`(eval-svc)加载 scene 行时,把 `default_params` 一并 `index.setDefaultParams(...)`(SQL 加取 `default_params` 列;SceneChangedEvent 刷新时随之更新 → live)。
- `EvalContext`(kernel)新增不可变字段 `Map<String,Object> sceneDefaultParams`;**加向后兼容构造器**(旧 5 参 `(tenantId,event,subject,metrics,now)` → 委托 6 参,`sceneDefaultParams=Map.of()`),保住大量既有测试调用点。
- `EvalContextAssembler.assemble`(kernel)新增 `sceneDefaultParams` 入参,构造 EvalContext 时带上。
- `EvalEngine`(kernel)评估时从 `index.getDefaultParams(event.tenantId(), event.sceneCode())` 取出,传给 `assemble(...)`。

### A3. timezone 消费(填 7 处 null 槽)
- 7 个调用点改:`TimeZoneResolver.resolve(node.params().get(ConditionParams.TIMEZONE), (String) ctx.sceneDefaultParams().get(SceneDefaultParams.TIMEZONE))`。
- 优先序 `条件 > scene > UTC` 已在 `TimeZoneResolver`,只是第二参从 `null` 换成真值。`TimeWindowEvaluator`/`OccurredAtEvaluator` 用 `params.get("timezone")` 的两处也同步改引用 `ConditionParams.TIMEZONE`(顺手消其魔法串)。

### A4. scene 创建/更新期 timezone 校验(judgment point 1,采纳)
- `SceneServiceImpl` 校验 `default_params` 里若有 `SceneDefaultParams.TIMEZONE`,其值须为合法 IANA ZoneId(`ZoneId.of` 不抛);非法抛 `IllegalArgumentException`(→ HTTP 400,message 含非法值)。与 PayloadFieldType type 校验同位置同风格(authoring 期 fail-fast,不拖到评估)。

## 4. 设计 —— B. 可传求值时钟 asOf

- evaluate HTTP 请求 DTO 新增可选 `Instant asOf`(ISO-8601;typed record 字段,非魔法串)。
- `EvalServiceImpl`:`Instant evalNow = req.asOf() != null ? req.asOf() : Instant.now();`,传既有 `evaluateWithContext(..., evalNow)`。kernel 签名不变。
- 语义:缺省 = 处理时刻(向后兼容);传固定时刻 = 可复现回放/测试;传 = `occurredAt` = 事件时刻语义(调用方自选)。
- `now` 仍"整棵 AST 共用一个实例"(不变),`$now` 占位符随之解析为传入的 asOf。
- dry-run 入口同样接 asOf(若 dry-run 走另一 DTO,同样加可选 asOf,缺省 now)。

## 5. 组件清单

新建:
- `rule-kernel/.../api/model/SceneDefaultParams.java`(常量)

修改:
- `rule-kernel/.../internal/index/SceneRuleIndex.java`(+defaultParams + get/set)
- `rule-kernel/.../api/model/EvalContext.java`(+sceneDefaultParams + 兼容构造器)
- `rule-kernel/.../internal/context/EvalContextAssembler.java`(assemble +入参)
- `rule-kernel/.../internal/engine/EvalEngine.java`(从 index 取 + 传 assemble)
- 7 个 evaluator(EqEvaluator/NeqEvaluator/BetweenEvaluator/NotBetweenEvaluator/DateComparisonSupport/TimeWindowEvaluator/OccurredAtEvaluator):timezone 取 scene 默认兜底 + ConditionParams.TIMEZONE 常量
- `rule-eval-svc/.../internal/snapshot/SceneSnapshotLoader.java`(载 default_params 进 index)
- `rule-eval-svc/.../internal/service/EvalServiceImpl.java`(asOf)
- evaluate 请求 DTO(rule-api,加 asOf)
- `rule-config-svc/.../internal/service/SceneServiceImpl.java`(timezone 合法性校验)

## 6. 错误处理

- **非法 scene timezone**:创建/更新期 `SceneServiceImpl` 校验拦下(A4)→ 400。评估期若仍遇非法(历史脏数据),`TimeZoneResolver` 对 scene 默认值**防御性兜底**:`ZoneId.of` 抛异常时退回 UTC(不让坏配置阻断评估)——resolver 增 try/catch 兜底(条件级 params.timezone 维持原抛出语义不变,仅 scene 默认值加兜底)。
- **非法 asOf**:请求反序列化 Instant 失败 → 框架 400。

## 7. 测试

单测:
- `SceneDefaultParams` 值 pin。
- `SceneRuleIndex` setDefaultParams/getDefaultParams(含缺省 Map.of())。
- `TimeZoneResolver`:scene 默认生效(条件无 timezone)、条件覆盖 scene、scene 非法→UTC 兜底。
- 各时间 evaluator:条件无 timezone 时用 ctx.sceneDefaultParams 的 timezone(如 TimeWindow 按 scene 时区判时段)。
- `EvalContext` 兼容构造器(5 参默认空 map)。
- `EvalContextAssembler.assemble` 带 sceneDefaultParams。
- `EvalServiceImpl`:传 asOf→该时刻被用(可复现);不传→Instant.now()。
- `SceneServiceImpl`:非法 timezone→拒。

**DB 端到端**:建带 `default_params.timezone=Asia/Shanghai` 的 scene + 一条 time.window 规则(条件不带 timezone)→ 传固定 asOf(落在/不落在时段)评估,结果按上海时区且可复现;PATCH 改 scene 时区 → 立即生效(live,无需republish);scene 配非法 timezone→400;清理。

## 8. 迁移与兼容

- 无 schema 变更(`scene.default_params` 列已存在,V1_0)。
- EvalContext 兼容构造器保旧调用点;evaluateWithContext 签名不变;asOf 可选缺省 now → 全向后兼容。

## 9. 后续(不含)

- currency 等其它 default_params 键的消费者(通道已通,按需加消费,无需再铺通道)。
- 引擎全局默认时区层(`条件>scene>引擎全局>UTC`)。
- pseudo-clock 框架(可推进时钟对象,replay/CEP 进阶)。
- D69 后续② OperatorCatalog 合并、③ SPI 算子自暴露 param schema(独立)。
