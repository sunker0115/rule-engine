# 高风险登录拦截(high-risk-login)

> **状态**:✅ 对齐当前实现(2026-06-10,含配置闭环 B 轮 D54:decision 独立写 API + 发布期冻结 name/priority/actions + PULL 决策无 action 校验),已通过 HTTP API 端到端跑通。
> 本案例是按**当前真实 API 契约**重写的样板——其余旧案例已归档于 [`../../archive/`](../../archive/)（`new-account-large-transfer` / `ticket-creation` / `user-register` / `time-conditions`），使用了已废弃的形态(见文末「旧案例过期点」),需逐个按本案例范式重写后移回业务域目录。

## 一、场景与业务目标

登录风控:用户登录时,若**交易金额 > 1000 且 用户风险分 ≥ 80**,判定为高风险,命中决策 `REJECT`。
本案例还演示 **payload 直接引用**:`amount` 作为事件自身事实,用 `valueRef=PAYLOAD` 直接引用,不再注册成 metric(`user.risk.score` 仍为受治理 metric)。
采用 **PULL 同步评估**(`dominantMode=PULL`):调用方 `POST /api/v1/rule/evaluate` 当场拿到 `finalDecision`,不派发异步 action。

## 二、配置概览

| 组成 | 取值 |
|---|---|
| Scene | `demo.login`,PULL,subjectType=USER,eventTypes=`[login]`,payloadSchema=`amount(number,必填)`/`country(string,必填)` |
| Metric | 仅 `user.risk.score`(LONG),ATTRIBUTE + `allowProvided=true`(tenant 级可用,无 scene 白名单);`amount` 不再注册成 metric,改走 payload 直接引用 |
| Decision | `REJECT`(priority 1)/`REVIEW`(2)/`PASS`(100),tenant 级,**均无 action**(PULL 场景) |
| Rule | `demo-high-risk-login`,AST_BOOLEAN:`AND( GT(payload amount,1000)[valueRef=PAYLOAD], GTE(metric user.risk.score,80) )`,preGate `ROLLOUT 100%`,绑定 `REJECT` |

文件:[`scene.json`](./scene.json) · [`metrics/metrics.json`](./metrics/metrics.json) · [`decisions/decisions.json`](./decisions/decisions.json) · [`rules/high-risk-login.json`](./rules/high-risk-login.json) · [`mock-events.json`](./mock-events.json) · [`expected-results.json`](./expected-results.json)

## 三、端到端 curl 剧本(可直接复制)

> 前置:`rule-app` 已起(`localhost:8080`)。`tenantId=9001` 是 `tenant.id`,`tenantCode=loadtest` 是对应的 `tenant.code`;换环境替换成你自己的租户。管理写接口都带 `X-Actor-Id`。

```bash
BASE=http://localhost:8080
H='-H Content-Type:application/json -H X-Actor-Id:demo-admin'

# 1) 建场景
curl -s $H -X POST "$BASE/admin/v1/scenes" --data @scene.json

# 2) 注册 metric(仅 user.risk.score;amount 是 payload 字段,走 valueRef=PAYLOAD 直接引用,不注册 metric)
#    tenantId/metricCode 走 query,body 不含这两者
curl -s $H -X POST "$BASE/admin/v1/metrics?tenantId=9001&metricCode=user.risk.score" \
  -d '{"name":"用户风险分","sourceType":"ATTRIBUTE","dataType":"LONG","params":{"table":"user_profile","column":"risk_score"},"cacheTtlSeconds":60,"allowProvided":true}'

# 3) 建 decision(D54:decision 是 tenant 级独立实体,发布期校验 decisionCode 必须存在,否则 DECISION_CODE_NOT_FOUND)
#    PULL 场景的 decision 必须无 action(同步返回不派发;发布期会拒绝带 action 的 decision)
python3 -c 'import json;[print(json.dumps(d)) for d in json.load(open("decisions/decisions.json"))]' | while read d; do
  curl -s $H -X POST "$BASE/admin/v1/decisions?tenantId=9001" -d "$d"; echo
done

# 4) 建规则草稿 → 记下返回的 ruleDefinitionId
RID=$(curl -s $H -X POST "$BASE/admin/v1/rules" --data @rules/high-risk-login.json | python3 -c 'import json,sys;print(json.load(sys.stdin)["data"]["ruleDefinitionId"])')
echo "ruleDefinitionId=$RID"

# 5) 发布(发布期从 decision_definition 冻结 name/priority/actions 进 rule_version.decision_bindings 快照)
curl -s $H -X POST "$BASE/admin/v1/rules/$RID/publish?tenantId=9001"

# 6) 评估——命中 REJECT(finalDecision.name="拒绝"、priority=1,均来自 decision_definition)
curl -s -H Content-Type:application/json -X POST "$BASE/api/v1/rule/evaluate" \
  -d '{"tenantCode":"loadtest","sceneCode":"demo.login","eventType":"login","subjectId":"user-001","eventId":"evt-hit-001","occurredAt":"2026-06-09T22:00:00+08:00","payload":{"amount":5000,"country":"CN"},"providedMetrics":{"user.risk.score":90}}'

# 7) 评估——未命中 MISS(amount=500)
curl -s -H Content-Type:application/json -X POST "$BASE/api/v1/rule/evaluate" \
  -d '{"tenantCode":"loadtest","sceneCode":"demo.login","eventType":"login","subjectId":"user-002","eventId":"evt-miss-001","occurredAt":"2026-06-09T22:01:00+08:00","payload":{"amount":500,"country":"CN"},"providedMetrics":{"user.risk.score":90}}'

# 8) dry-run——命中 + 完整 nodeTrace
curl -s -H Content-Type:application/json -X POST "$BASE/api/v1/rule/dry-run" \
  -d '{"tenantCode":"loadtest","sceneCode":"demo.login","eventType":"login","subjectId":"user-003","eventId":"evt-dry-001","occurredAt":"2026-06-09T22:02:00+08:00","payload":{"amount":8000,"country":"US"},"providedMetrics":{"user.risk.score":85}}'

# 9) 查询(异步落库,稍等几秒)
curl -s "$BASE/admin/v1/evaluation-sessions?tenantId=9001&sceneCode=demo.login"
curl -s "$BASE/admin/v1/audit-logs?tenantId=9001&targetType=rule_definition&targetId=$RID"
curl -s "$BASE/sdk/v1/snapshots?tenantId=9001&scenes=demo.login"
```

预期结果见 [`expected-results.json`](./expected-results.json):命中 → `ruleHit=true` + `finalDecision={code:REJECT, name:"拒绝", priority:1}`(name/priority 来自 decision);未命中 → `ruleHit=false` + `status=MISS`。

## 四、当前实现注意点(踩坑记录)

1. **payload 字段用 `"valueRef":"PAYLOAD"` 直接引用,无需注册 metric**。ConditionNode 加 `"valueRef":"PAYLOAD"`(省略时默认 `METRIC`),此时 `metricCode` 复用为 payload 字段名。发布期 `MetricDependencyCollector` **跳过** PAYLOAD 节点(不再要求其有 ACTIVE metric 版本),改由 `PublishService` 校验该字段已在 `scene.payloadSchema` 声明;评估期 `EvalContextAssembler` 把事件 `payload` 各字段注入值映射,PAYLOAD 节点直接从 payload 取值(无需 providedMetrics)。
   - **配置判据(谁走 payload / 谁走 metric)**:需要取数 / 需要权威保护(`allowProvided=false`) / 跨规则复用 / 要版本化 / 要下发 SDK / 要影响面查询 —— 任一为 yes 即用 **metric**,全为 no 即用 **payload**。`amount` 永远是 payload(这笔交易的事实);`user.risk.score` 永远是 metric(受治理指标)。
2. **比较算子用 `conditionType` + `metricCode`(顶层) + `params`**:`GT/GTE/LT/LTE` 用 `params.threshold`,`EQ/NEQ` 用 `params.value`,`IN/NOT_IN` 用 `params.values`,`BETWEEN` 用 `params.min/max`(见 `kernel ConditionType` 与 `sdk Condition`)。**没有 `METRIC_COMPARE`/`PAYLOAD_COMPARE` 这类算子**(payload 字段走 `valueRef=PAYLOAD` + `metricCode` 复用为字段名,不注册 metric)。
3. **decision 是 tenant 级独立实体,发布前必须先建**(D54/D27 补齐)。`POST /admin/v1/decisions` 建 `decision_definition`(code/name/priority/description/actions);发布期校验规则绑定的 decisionCode 必须存在,否则 `DECISION_CODE_NOT_FOUND`,并把 decision 的 `name`/`priority`/`actions` 冻结进 `rule_version.decision_bindings` 快照。命中时 `finalDecision` 即带 `name`/`priority`/`actions`(不再是空串/0)。**PULL 场景的 decision 必须无 action**(同步返回不派发;发布期拒绝 PULL+带 action 的 decision);action 派发是 PUSH/HYBRID 场景的事。
4. **ATTRIBUTE metric 发布免安全校验**;`SQL_AGGREGATE`/`EXTERNAL_HTTP` 才过 `MetricSafetyValidator`(拒 DB 时间函数/`${}` 拼接 + 资源名注册)。本地无真实数据源,用 ATTRIBUTE + `allowProvided` + `providedMetrics` 喂值跑通。
5. **评估请求体的租户字段是 `tenantCode`**(不是 `tenantId`);管理接口才用 `tenantId`(= `tenant.id` 数字)。

## 五、旧案例过期点(重做其余案例时逐项替换)

| 旧形态(已废弃) | 当前形态 |
|---|---|
| `scene.json` 含 `metricBindings`/`actionBindings`/`decisions`/`decisionStrategy` | `POST /scenes` 只收 `tenantId/sceneCode/name/description/dominantMode/subjectType/eventTypes/payloadSchema/defaultParams`;metric 在 tenant 级可用(无 scene 白名单,D54)、decision 走 `POST /admin/v1/decisions` 单独建(tenant 级)、action 归 decision(无 scene_action_binding,D54) |
| `payloadSchema` 为对象 `{字段:{type,required}}` | 数组 `[{name,type,required,...}]` |
| 条件用 `conditionType:"METRIC_COMPARE"/"PAYLOAD_COMPARE"` + `params.{metricCode,operator,value,field}` | `conditionType:"GT"/"GTE"/...` + 顶层 `metricCode` + `params.threshold`(payload 字段用 `valueRef=PAYLOAD` 直接引用,不注册 metric) |
| preGate 用 `type` | 用 `gateType` |
| metric `cachePolicyDefault:{ttl}` | `cacheTtlSeconds` |
| mock event 用 `_mockMetrics` | 真实字段 `providedMetrics`,且租户字段为 `tenantCode` |

## 六、相关契约 / 决策

- API 字段命名以 [`../../10-api-contract.md`](../../10-api-contract.md) 为准(本案例已对齐其 §三评估 / §四规则管理 / §六查询)。
- 算子集:`rule-kernel` `ConditionType`;AST 构造:`rule-sdk` `Condition`。
- 发布校验:`PublishService` + `MetricDependencyCollector` + `MetricSafetyValidator`。
