# 场景输入参数清单 — 设计

> 状态:设计待评审(2026-06-10,brainstorming 达成)。给"对场景发事件"的调用方一个精确可校验的输入契约;公开评估接口只收 payload 事实,metric 全归引擎。

## 一、背景与目标

当前调用方评估时要填两类业务字段:`payload`(事件事实)+ `providedMetrics`(指标值)。问题:
- **事件事实被迫伪装成 metric**:规则条件节点(`ConditionNode`)只有 `metricCode` 一个取值通道,连 `amount` 这种事件自带事实也要注册成 metric + 走 `providedMetrics` 喂(同一字段填三遍)。
- **`providedMetrics` 泄漏引擎内部 taxonomy**:调用方被迫知道"payload vs metric vs providedMetrics"这些本该是引擎内部的概念。
- **没有调用方导向的输入契约**:调用方不知道"这个场景要我精确传哪些字段、什么类型",也无校验落点。

**目标**:调用方的心智极简——"查一次这个场景要我传哪些事件事实(名+类型),照着传 payload,剩下引擎自己搞定"。

**核心架构主张**:
1. **公开评估接口只收 `payload`(事件事实)**,由 `scene.payloadSchema` 声明、规则用 payload 直接引用。
2. **`providedMetrics` 从公开接口彻底拿掉**;metric = 100% 引擎侧(按 `sourceType` 自取)。
3. **"上游注入受治理 metric"是另一条非公开路径**(嵌入式 SDK 宿主注入 / Job 预算),内部 `RuleEvent` 仍持 providedMetrics,但通用 HTTP 调用方碰不到。
4. **场景输入清单**=该场景 active 规则实际引用的 payload 字段子集,调用方查发现接口拿到精确要传什么,评估期据此校验。

## 二、依赖

- **依赖 A = payload 直接引用**(`docs/superpowers/specs/2026-06-09-payload-direct-reference-design.md`,现成 spec/plan,**未执行**)。本设计建在"payload 事实 / metric 指标已由 `valueRef` 分开"之上。
- **执行先后**:先 A 后 B,两份 spec 各自独立。B 的发布期收集 / 评估期校验都以 A 的 `valueRef=PAYLOAD` 节点为输入。

## 三、调用粒度(已对齐)

调用方**对「场景」发事件**(现状模型):一个事件触发该场景所有匹配规则,合成一个 `finalDecision`,**不指定跑哪条规则**。因此:
- **参数清单对外是场景级**:该场景所有 active 规则引用的 payload 字段的**并集**;
- 每条规则维护自己的 payload 依赖(授权/校验侧),对外聚合到场景。

## 四、设计

### 4.1 数据模型(发布期快照)

- 发布期扩展依赖收集:除现有 `metric_dependencies`,新增收集本规则引用的 **payload 字段**(A 的 `valueRef=PAYLOAD` 节点),落 `rule_version.payload_dependencies`。
- 结构:`List<PayloadDependency>`,`PayloadDependency = { name, dataType, required }`。`name` 来自节点;`dataType` / `required` 从 `scene.payloadSchema` 对应字段取(发布期已有 payloadSchema 校验,顺手取)。
- 与 `metric_dependencies` 同套路、同 D6(快照不可变 + 评估零额外查询);**DB 迁移**:`rule_version` 加 `payload_dependencies` JSON 列(`@TableField(typeHandler = Jackson3TypeHandler.class)`,typed)。
- **场景级输入清单**=该场景所有 status=ACTIVE 的 `rule_version.payload_dependencies` 的**并集**(同名字段去重,类型/required 取 payloadSchema 的声明)。

### 4.2 快照下发(评估侧)

- `RuleVersionSnapshot`(rule-kernel)加 `payloadDependencies` 字段,随快照下发。评估侧加载候选快照时即拿到清单,**零额外查询**做入参校验。
- 序列化:与现有 `metricDependencies` 对称(`AstJsonCodec` / `SnapshotAssembler` 同步)。

### 4.3 对外契约

- **eval 请求改造**:`EvalEventRequest`(rule-api)**删 `providedMetrics`**,只留 `payload` 等字段。
  - 内部 `RuleEvent`(rule-kernel)**保留** `providedMetrics` 字段,供 SDK 宿主注入 / Job 预算这条**非公开**路径用——公开 HTTP `EvalController` 构造 `RuleEvent` 时不再从请求体填 providedMetrics(恒空)。
- **新发现接口**:`GET /api/v1/rule/scenes/{sceneCode}/input-manifest?tenantCode=xxx[&eventType=xxx]` → 返回 `[{ name, dataType, required }]`(该场景 active 规则实际引用的 payload 字段;带 `eventType` 时收窄到会被该事件触发的规则)。
- **作废 `getProvidedMetrics`**:`MetadataService.getProvidedMetrics` + 其端点删除(公开侧无 provided metric 概念了)。`getSceneMetadata` 保留(配置侧元数据,前端配规则用),其 `availableMetrics` 仍是 tenant 级 ACTIVE metric。

### 4.4 评估期校验

- 评估入口加载将要评估的 active 规则(候选快照,可按 eventType 收窄)的 `payloadDependencies` 并集,校验请求 `payload`:
  - **必填字段全到**(`required=true` 的都在 payload 里)→ 否则拒绝,errorCode `MISSING_REQUIRED_INPUT`,报缺哪个字段;
  - **基础类型匹配**(对齐 A 的 payloadSchema 类型映射:`number→DECIMAL`、`integer→LONG`、`string→STRING`、`boolean→BOOLEAN`)→ 否则 `INPUT_TYPE_MISMATCH`,报哪个字段类型不符;
  - 多塞的、没规则引用的字段 → **忽略**(当额外上下文,可照常记录到 trace/session)。
- 走现有 `IllegalArgumentException → HTTP 400` 约定;与发布期 `UNRESOLVED_VARIABLE`(规则别引用没声明的字段)**正交**:一个管授权期"规则别越界引用",一个管调用期"调用方别漏传 / 错类型"。
- `required=false` 的可选字段漏传不算违约,正常走缺失。

## 五、影响面清单

- **rule-kernel**:`RuleVersionSnapshot`(+`payloadDependencies`)、新 `PayloadDependency` record、`AstJsonCodec`/`SnapshotAssembler` 序列化、`RuleEvent`(providedMetrics 保留不变)。
- **rule-config-svc**:发布期 payload 依赖收集(扩展现有 collector / PublishService)+ 落 `rule_version.payload_dependencies` + 场景级清单查询;DB 迁移加列;`MetadataService.getProvidedMetrics` 删。
- **rule-eval-svc**:评估入口按快照 `payloadDependencies` 校验入参 + 新 errorCode。
- **rule-api**:`EvalEventRequest` 删 providedMetrics;`EvalController` 不再填 providedMetrics;新 input-manifest 端点;删 provided-metrics 端点。
- **docs / 数据**:`01-concepts`(payload/metric 输入契约)、`10-api-contract`(eval 请求体改、新发现接口、删 provided-metrics)、`00-decisions`(追加决策);demo/examples 重做(amount 走 payload、user.risk.score 改引擎可解析或去掉)。

## 六、测试

- 发布期:规则引用 payload 字段 X(`valueRef=PAYLOAD`)→ `payload_dependencies` 快照含 `{X, 类型, required}`;`valueRef=METRIC` 字段不混入 payload 依赖。
- 场景级清单:多条 active 规则的 payload 依赖并集、同名去重、类型/required 取 payloadSchema。
- `RuleVersionSnapshot.payloadDependencies` 序列化往返。
- 评估校验:漏必填 → `MISSING_REQUIRED_INPUT`(400);类型不符 → `INPUT_TYPE_MISMATCH`(400);多塞字段 → 忽略且评估正常;全对 → 正常评估命中。
- 发现接口:返回该场景(及 eventType 收窄)的 payload 字段清单正确。
- `EvalEventRequest` 不再含 `providedMetrics`(编译 + 契约)。

## 七、已定决策(brainstorming 2026-06-10)

1. **范围 = A + B**:A(payload 直接引用,现成 spec)先执行;B(本设计,参数清单)依赖 A。
2. **调用粒度 = 场景级**:对场景发事件,清单是场景级并集。
3. **公开评估只收 payload**:`providedMetrics` 从公开接口删除;metric 全归引擎;注入走非公开路径(SDK/Job)。
4. **缺必填字段 = 整体拒绝(400)**,不降级。
5. **清单来源 = 发布期快照**(`rule_version.payload_dependencies`,随 `RuleVersionSnapshot` 下发),评估/发现期读快照并集,不重扫 AST。

## 八、取舍(已接受)

- **每个被规则引用的 metric 必须引擎可解析**(真实数据源 / 从 subject 取);本地"用 providedMetrics 喂 metric"的偷懒法挪到 SDK/dev 路径——换来公开调用方契约的彻底干净。
- 清单是发布期快照:改规则后清单随发布更新(已发布版本用旧快照,符合 D6);非即时。
