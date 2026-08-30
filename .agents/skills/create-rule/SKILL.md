---
name: create-rule
description: Use when the user wants to create a business rule in the rule-engine project — guides a short conversation to collect rule intent, conditions, and decisions, then produces ready-to-run curl commands. Triggers on phrases like "创建规则", "加一条规则", "新规则", "create rule", or when the user describes a business condition to enforce.
---

# 规则创建向导

通过 3–5 轮对话收集必要信息,生成完整 API 调用序列(decision → rule 草稿 → 发布),可选直接执行。

## 对话流程

```
1. 意图          → 用一句话描述规则做什么
2. 场景/租户     → sceneCode + tenantId(数字)/tenantCode(评估用)
3. 条件          → 逐条确认(conditionType + 关键参数)
4. 决策绑定      → decisionCode(若不存在先建)
5. 触发事件      → triggerEventTypes(可选,空=通配)
6. 生成 + 执行  → 输出 curl 序列,问是否直接跑
```

**每步一问**,先确认再问下一步。条件较复杂时先问逻辑关系(AND/OR/NOT)。

---

## conditionType 速查

| 意图 | conditionType | 必填 params(键名) | metricCode |
|---|---|---|---|
| 数值大于 | `GT` | `threshold` | 指标码 |
| 数值小于 | `LT` | `threshold` | 指标码 |
| 数值区间 | `BETWEEN` | `min`, `max` | 指标码 |
| 等于 | `EQ` | `threshold` | 指标码 |
| 不等于 | `NEQ` | `threshold` | 指标码 |
| 属于集合 | `IN` | `values`(数组) | 指标码 |
| 不属于集合 | `NOT_IN` | `values`(数组) | 指标码 |
| 集合包含元素 | `CONTAINS` | `element` | 指标码(LIST类型) |
| 正则匹配 | `MATCHES` | `regex` | 指标码(STRING) |
| 前缀匹配 | `STARTS_WITH` | `prefix` | 指标码(STRING) |
| 后缀匹配 | `ENDS_WITH` | `suffix` | 指标码(STRING) |
| 日期早于 | `DATE_BEFORE` | `threshold`(ISO日期/`$now`) | 指标码(DATE/DATETIME) |
| 日期晚于 | `DATE_AFTER` | `threshold`(ISO日期/`$now`) | 指标码(DATE/DATETIME) |
| 营业时段 | `time.window` | `start`,`end`(HH:mm),`daysOfWeek`(可选) | **null** |
| 事件时间区间 | `time.occurred_at` | `operator`(BEFORE/AFTER/BETWEEN),`value`/`start`+`end` | **null** |
| payload 字段 | 任意 conditionType | 同上 | 字段名,加 `"valueRef":"PAYLOAD"` |

> `time.*` 内置算子 metricCode=null、不走指标取数;其余均需填 metricCode。

---

## AST JSON 结构

**单条件(最常见)**
```json
{
  "type": "ConditionNode",
  "conditionType": "GT",
  "metricCode": "user.order.count.30d",
  "params": { "threshold": 3 }
}
```

**多条件 AND**
```json
{
  "type": "AndNode",
  "children": [
    { "type": "ConditionNode", "conditionType": "GT", "metricCode": "amount", "params": {"threshold": 100} },
    { "type": "ConditionNode", "conditionType": "IN",  "metricCode": "channel", "params": {"values": ["APP","WEB"]} }
  ]
}
```

**取反**
```json
{ "type": "NotNode", "child": { "type": "ConditionNode", ... } }
```

**payload 字段(不走指标,直读 event.payload)**
```json
{
  "type": "ConditionNode",
  "conditionType": "EQ",
  "metricCode": "action_type",
  "valueRef": "PAYLOAD",
  "params": { "threshold": "login" }
}
```

---

## API 调用序列

```bash
# 0. 若 decision 不存在先建
curl -X POST "http://localhost:8080/admin/v1/decisions?tenantId=<tenantId>" \
  -H "Content-Type: application/json" -H "X-Actor-Id: <actor>" \
  -d '{"code":"<decisionCode>","name":"<name>","priority":<priority>}'

# 1. 建规则草稿
curl -X POST http://localhost:8080/admin/v1/rules \
  -H "Content-Type: application/json" -H "X-Actor-Id: <actor>" \
  -d '{
    "tenantId": "<tenantId>",
    "sceneCode": "<sceneCode>",
    "code": "<ruleCode>",
    "name": "<ruleName>",
    "kind": "AST_BOOLEAN",
    "conditionAst": <AST_JSON>,
    "decisionBindings": [{"decisionCode": "<decisionCode>"}],
    "triggerEventTypes": [<eventTypes>]
  }'
# 返回 ruleDefinitionId

# 2. 发布
curl -X POST "http://localhost:8080/admin/v1/rules/<ruleDefinitionId>/publish?tenantId=<tenantId>" \
  -H "X-Actor-Id: <actor>"
```

---

## 验证规则(发布后)

```bash
curl -X POST http://localhost:8080/api/v1/rule/evaluate \
  -H "Content-Type: application/json" \
  -d '{
    "tenantCode": "<tenantCode>",
    "sceneCode":  "<sceneCode>",
    "eventType":  "<eventType>",
    "subjectId":  "test-user",
    "eventId":    "test-1",
    "payload":    {},
    "asOf":       "<ISO-8601, 可选>"
  }'
```

---

## 示例全流程

**用户说**:"用户 30 天内下单超过 3 次,给 LOYAL 决策"

**向导收集**:
- tenantId=9100,sceneCode=user.behavior,triggerEventTypes=[ORDER_PLACED]
- conditionType=GT,metricCode=user.order.count.30d,threshold=3
- decisionCode=LOYAL(已存在)

**生成**:
```bash
curl -X POST http://localhost:8080/admin/v1/rules \
  -H "Content-Type: application/json" -H "X-Actor-Id: demo" \
  -d '{
    "tenantId":"9100","sceneCode":"user.behavior","code":"rule-loyal-user",
    "name":"忠实用户","kind":"AST_BOOLEAN",
    "conditionAst":{"type":"ConditionNode","conditionType":"GT",
                    "metricCode":"user.order.count.30d","params":{"threshold":3}},
    "decisionBindings":[{"decisionCode":"LOYAL"}],
    "triggerEventTypes":["ORDER_PLACED"]
  }'
```

---

## 常见问题

| 情况 | 处理 |
|---|---|
| 用户说"大于但包含等于" | 用 `GTE`(大于等于),threshold 不变 |
| 多个决策 | decisionBindings 加多个 `{decisionCode}` |
| 规则只部分流量触发 | preGates 加 `{"type":"ROLLOUT","params":{"percentage":N}}` |
| time.window 不写 timezone | 用 scene.default_params.timezone 兜底 |
| 需要 OR 逻辑 | type 改 `OrNode`,children 同 AndNode |
| 想先 dry-run 不正式发布 | 用 `POST /api/v1/rule/dry-run` + `ruleVersionId` 参数 |
