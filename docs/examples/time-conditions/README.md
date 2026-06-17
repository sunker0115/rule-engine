# 示例:scene 默认时区 + time.window + 可复现求值时钟(asOf)

> 演示 D69 后续①(时间求值正确性):规则用 `time.window` 判"是否营业时段",**条件本身不带 timezone**,改由 **scene 默认时区**(`scene.default_params.timezone`)兜底;调用方传 `asOf` 控制求值时刻,结果**可复现**;改 scene 时区 **live 生效**(无需重发规则/重启)。
>
> 全部 curl 已对本仓当前契约(D60 纯决策化 / D69 schema 收口后)实跑验证。

## 前置

- 起 rule-app(打包产物运行,确认 Flyway 迁移完成、服务就绪)。
- 一个真实租户:本示例用 `tenant_id=9100`(code `samples`)。**admin API 用数字 `tenantId`,评估 API 用租户 `tenantCode`。** 换成你的租户时两者对应同一租户。
- 约定:`X-Actor-Id` 头标识操作人;`H='-H Content-Type:application/json -H X-Actor-Id:demo'`。

## 1. 建 scene(默认时区 Asia/Shanghai)

```bash
curl -X POST http://localhost:8080/admin/v1/scenes \
  -H "Content-Type: application/json" -H "X-Actor-Id: demo" -d '{
  "tenantId":"9100","sceneCode":"tzdemo.win","name":"时段促活",
  "dominantMode":"PULL","subjectType":"USER","eventTypes":["LOGIN"],
  "payloadSchema":[{"name":"action_type","type":"STRING","required":false}],
  "defaultParams":{"timezone":"Asia/Shanghai"}
}'
# → {"success":true,"data":{"id":<sceneId>}}
```

> 非法 timezone 在创建期即被拒(fail-fast):
> ```bash
> # defaultParams.timezone = "Asia/Xxx" → HTTP 400
> # {"errorCode":"INVALID_ARGUMENT","message":"非法 scene default_params.timezone=Asia/Xxx（须为合法 IANA 时区名，如 Asia/Shanghai）"}
> ```

## 2. 建 decision

```bash
curl -X POST "http://localhost:8080/admin/v1/decisions?tenantId=9100" \
  -H "Content-Type: application/json" -H "X-Actor-Id: demo" -d '{
  "code":"TZDEMO_RWD","name":"发奖励","priority":1
}'
# → {"success":true,"data":<decisionId>}
```

## 3. 建规则(time.window,**params 不带 timezone** → 用 scene 默认)

```bash
curl -X POST http://localhost:8080/admin/v1/rules \
  -H "Content-Type: application/json" -H "X-Actor-Id: demo" -d '{
  "tenantId":"9100","sceneCode":"tzdemo.win","code":"rule-tz-win","name":"工作时段","kind":"AST_BOOLEAN",
  "conditionAst":{
    "type":"ConditionNode","conditionType":"time.window","metricCode":null,
    "params":{"start":"09:00","end":"22:00","daysOfWeek":["MON","TUE","WED","THU","FRI"]}
  },
  "decisionBindings":[{"decisionCode":"TZDEMO_RWD"}],
  "triggerEventTypes":["LOGIN"]
}'
# → {"success":true,"data":{"ruleDefinitionId":<ruleId>,"ruleVersionId":...,"status":"DRAFT"}}
```

## 4. 发布

```bash
curl -X POST "http://localhost:8080/admin/v1/rules/<ruleId>/publish?tenantId=9100" -H "X-Actor-Id: demo"
# → 200,规则 ACTIVE(RulePublishedEvent 异步热更倒排索引,稍候即可评估)
```

## 5. 评估 —— scene 默认时区生效 + asOf 可复现

`time.window` 判 `asOf` 投影到 **scene 默认时区(上海)** 的墙上时间是否落在 09:00–22:00 的工作日。

```bash
EVAL() { curl -s -X POST http://localhost:8080/api/v1/rule/evaluate -H "Content-Type: application/json" -d "$1"; }

# 5a. asOf=2026-06-15T02:00:00Z(周一);上海 = 10:00,时段内 → 命中 TZDEMO_RWD
EVAL '{"tenantCode":"samples","sceneCode":"tzdemo.win","eventType":"LOGIN","subjectId":"u1","eventId":"e1","payload":{"action_type":"login"},"asOf":"2026-06-15T02:00:00Z"}'
# → data.ruleHit=true, finalDecision.code="TZDEMO_RWD"

# 5b. asOf=2026-06-15T16:00:00Z;上海 = 次日 00:00,时段外 → 不命中
EVAL '{...,"eventId":"e2","asOf":"2026-06-15T16:00:00Z"}'
# → data.ruleHit=false

# 5c. 同 asOf 重复(可复现)→ 与 5a 一致命中
EVAL '{...,"eventId":"e3","asOf":"2026-06-15T02:00:00Z"}'
# → data.ruleHit=true
```

> 不传 `asOf` 则用 `Instant.now()`(处理时刻);传固定 `asOf` 用于可复现回放 / 测试;传 = `occurredAt` 取事件时刻语义。

## 6. 改 scene 时区 —— live 生效(无需重发规则/重启)

```bash
curl -X PATCH "http://localhost:8080/admin/v1/scenes/tzdemo.win?tenantId=9100" \
  -H "Content-Type: application/json" -H "X-Actor-Id: demo" -d '{"tenantId":"9100","defaultParams":{"timezone":"UTC"}}'
# updateScene 发 SceneChangedEvent → eval 索引热更 default_params

# 同 asOf=2026-06-15T02:00:00Z:现按 UTC = 02:00,时段外 → 翻成不命中
EVAL '{...,"eventId":"e4","asOf":"2026-06-15T02:00:00Z"}'
# → data.ruleHit=false（证明 scene 时区变更 live 生效）
```

## 7. 清理

```bash
curl -X POST "http://localhost:8080/admin/v1/rules/<ruleId>/disable?tenantId=9100" -H "X-Actor-Id: demo"
curl -X POST "http://localhost:8080/admin/v1/scenes/tzdemo.win/disable?tenantId=9100" -H "X-Actor-Id: demo" || true
# 彻底清(开发库):DELETE FROM rule_version WHERE rule_definition_id=<ruleId>;
#                  DELETE FROM rule_definition WHERE id=<ruleId>;
#                  DELETE FROM scene WHERE tenant_id=9100 AND code='tzdemo.win';
#                  DELETE FROM decision_definition WHERE tenant_id=9100 AND code='TZDEMO_RWD';
```

## 优先序与边界

- **时区优先序**:`条件 params.timezone` > `scene.default_params.timezone` > `UTC`。条件显式带 timezone 时覆盖 scene 默认;scene 默认非法(脏数据)时求值期兜底 UTC(创建期已 fail-fast 拦截合法性)。
- **被解释的时间**已在上下文里:`time.window` 用 `ctx.now()`(= asOf 或 Instant.now());`time.occurred_at` 用 `event.occurredAt`;DATE_BEFORE/AFTER 用 metric 值。timezone 是把这些时刻投影成墙上时间的"镜片"。
- 设计见 `docs/superpowers/specs/archive/2026-06-13-time-evaluation-correctness-design.md`(D69 后续①)。
