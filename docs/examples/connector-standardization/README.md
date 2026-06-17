# 连接器标准化(connector-standardization)

> **状态**:✅ 对齐当前实现(D72,连接器标准化全栈 P1 写侧 + P2 运行时含 OAuth2 + P3 测试端点/conformance + P4 前端)。
> 本案例演示 **`EXTERNAL_HTTP` 声明式连接器**端到端:连接器是**可复用命名资源**(不塞 `metric.params`),metric 经 `params={connector,vars}` 引用;descriptor 声明 request 模板 / response 映射 / 鉴权 / 弹性 / 错误映射。契约见 [`../../04-extension.md`](../../04-extension.md) §4.5(C1–C5),设计见 `specs/archive/2026-06-15-metric-fetch-standardization-design.md`。

## 一、场景与业务目标

登录风控:用户登录时调外部**风控评分服务**取 `user.risk.score`,**分数 > 80** 判定高风险,命中决策 `REJECT`。
评分服务返回 `{code,msg,data}` 信封(`code==0` 为成功,分数在 `data.score`),用 BEARER token 鉴权——这正是声明式连接器要归一的"各家外壳各异"形态:一份 descriptor 描述清楚,metric 引用即可,无需写一行取数代码。
采用 **PULL 同步评估**(`dominantMode=PULL`):`POST /api/v1/rule/evaluate` 当场拿到 `finalDecision`。

## 二、配置概览

| 组成 | 取值 |
|---|---|
| Endpoint(infra) | `risk-svc`,配在 `engine.rule.fetch.endpoints`(baseUrl + 超时);凭证 `risk-svc-token` 配在 `engine.rule.fetch.credentials`(值来自 env/secrets) |
| Connector | `risk-score-connector`,`POST /v1/score`,`successWhen code==0`、`valuePath=data.score`、`auth=BEARER(tokenRef=risk-svc-token)`、`errorMapping`(信封 `40401`→NOT_FOUND、429→UPSTREAM_ERROR) |
| Metric | `demo.user.risk.score`,`sourceType=EXTERNAL_HTTP`,`dataType=DOUBLE`,`params={connector:"risk-score-connector", vars:{channel:"login"}}` |
| Decision | `REJECT`(priority 1)/`PASS`(100),tenant 级 |
| Rule | `demo-high-risk-score`,AST_BOOLEAN:`GT(metric demo.user.risk.score, 80)`,preGate `ROLLOUT 100%`,绑定 `REJECT` |

文件:[`connectors/risk-score-connector.json`](./connectors/risk-score-connector.json) · [`metrics/metrics.json`](./metrics/metrics.json) · [`scene.json`](./scene.json) · [`decisions/decisions.json`](./decisions/decisions.json) · [`rules/high-risk-score.json`](./rules/high-risk-score.json)

## 三、前置:infra 配 endpoint + credential

连接器的 `endpointRef` / auth `*Ref` 指向 infra 注册的命名资源(baseUrl/密钥**只在这层**,灭 SSRF、不落 connector/metric)。在 `rule-app` 的 `application.yml`(或环境覆盖)配:

```yaml
engine:
  rule:
    fetch:
      timeout-ms: 800
      endpoints:
        - name: risk-svc
          base-url: https://risk.internal.example.com
          connect-timeout-ms: 200
          read-timeout-ms: 300
      credentials:
        - name: risk-svc-token
          value: ${RISK_SVC_TOKEN}      # 实际密钥来自 env/secrets，不写进 yml/库
```

> `endpoints[].name` 即 descriptor 的 `endpointRef`;`credentials[].name` 即 auth 的 `tokenRef`/`credentialRef`/`clientIdRef`/`clientSecretRef`。改这些要重启服务(infra 配置,非热加载);连接器 descriptor 本身热加载(见步骤 7)。

## 四、端到端 curl 剧本(可直接复制)

> 前置:`rule-app` 已起(`localhost:8080`)、迁移(含 V1_34)执行完;`tenantId=9001`/`tenantCode=loadtest` 换成你自己的租户。管理写接口都带 `X-Actor-Id`。
> 按**依赖顺序**:被引用的资源先建——connector → metric(引用 connector) → decision → rule(引用 metric) → publish。

```bash
BASE=http://localhost:8080
H='-H Content-Type:application/json -H X-Actor-Id:demo-admin'

# 1) 建 connector(connectorCode 走 query param，body 是 {name,descriptor})
#    descriptor 是 typed ConnectorDescriptor，写时校验 endpointRef 已注册、auth 合法、占位符闭合
curl -s $H -X POST "$BASE/admin/v1/connectors?tenantId=9001&connectorCode=risk-score-connector" \
  --data @connectors/risk-score-connector.json

# 2) 建 EXTERNAL_HTTP metric(metricCode 走 query param)，params 引用步骤 1 的 connector + vars
curl -s $H -X POST "$BASE/admin/v1/metrics?tenantId=9001&metricCode=demo.user.risk.score" \
  --data @metrics/metrics.json

# 3) 建场景
curl -s $H -X POST "$BASE/admin/v1/scenes" --data @scene.json

# 4) 建 decision(tenant 级独立实体,发布期校验 decisionCode 必须存在)
python3 -c 'import json;[print(json.dumps(d)) for d in json.load(open("decisions/decisions.json"))]' | while read d; do
  curl -s $H -X POST "$BASE/admin/v1/decisions?tenantId=9001" -d "$d"; echo
done

# 5) 建规则草稿 → 记下 ruleDefinitionId(草稿即跑全套校验:metric 须 ACTIVE、decision 须存在)
RID=$(curl -s $H -X POST "$BASE/admin/v1/rules" --data @rules/high-risk-score.json | python3 -c 'import json,sys;print(json.load(sys.stdin)["data"]["ruleDefinitionId"])')
echo "ruleDefinitionId=$RID"

# 6) 发布(冻结 metric 依赖 (metricCode,version) 进 rule_version.metric_dependencies)
curl -s $H -X POST "$BASE/admin/v1/rules/$RID/publish?tenantId=9001"

# 7) 评估——引擎按 metric.sourceType 路由 DeclarativeHttpConnectorHandler:
#    解析 connector descriptor → 渲染 POST /v1/score(body 填 subjectId/vars.channel/payload.ip)
#    → 注入 BEARER token → 判 successWhen(code==0) → 取 data.score → GT 80 → REJECT
curl -s -H Content-Type:application/json -X POST "$BASE/api/v1/rule/evaluate" \
  -d '{"tenantCode":"loadtest","sceneCode":"demo.connector","eventType":"login","subjectId":"user-001","eventId":"evt-conn-001","occurredAt":"2026-06-15T22:00:00+08:00","payload":{"ip":"1.2.3.4"}}'

# 8) 改 connector 验热失效——PUT 原地更新 descriptor(不升版),发 ConnectorChangedEvent
#    → ConnectorDefinitionResolver.invalidate 主动失效 Caffeine 缓存,下次评估读到新 descriptor，不重启
#    (这里把 successWhen 阈值路径/valuePath 调一下演示，实际改完再次评估应反映新映射)
curl -s $H -X PUT "$BASE/admin/v1/connectors/risk-score-connector?tenantId=9001" \
  --data @connectors/risk-score-connector.json

# 9) 自助测试:不经 metric 直测 connector,实打实发一次,返回分阶段 trace
#    (renderedRequest / rawResponse / successMatched / mappedValue / errorCode)——映射写错当场可见
curl -s $H -X POST "$BASE/admin/v1/connectors/risk-score-connector:test?tenantId=9001" \
  -d '{"sampleVars":{"channel":"login"},"samplePayload":{"ip":"1.2.3.4"},"sampleSubjectId":"user-001"}'

# 10) 自助测试:经 metric 测(通吃任意 sourceType),验证 params.connector+vars 串起来正确
curl -s $H -X POST "$BASE/admin/v1/metrics/demo.user.risk.score:test?tenantId=9001" \
  -d '{"sampleVars":{"channel":"login"},"samplePayload":{"ip":"1.2.3.4"},"sampleSubjectId":"user-001"}'

# 11) 查落库(异步,稍等几秒):session + connector_definition + 审计
curl -s "$BASE/admin/v1/evaluation-sessions?tenantId=9001&sceneCode=demo.connector"
curl -s "$BASE/admin/v1/connectors?tenantId=9001"
curl -s "$BASE/admin/v1/audit-logs?tenantId=9001&targetType=rule_definition&targetId=$RID"
```

## 五、预期结果

| 步骤 | 预期 |
|---|---|
| 1 建 connector | `201`,`data` = 新行 id;`connector_definition` 表新增一行(status=ACTIVE) |
| 1 反例(endpointRef 未注册) | 写时校验拒绝(`risk-svc` 不在 `engine.rule.fetch.endpoints` 时报资源未注册) |
| 2 建 metric | `200`/`data`=id;`metric_definition.params` 落 `{connector,vars}`(非旧 `{endpoint,path,jsonPath}`) |
| 6 发布 | `rule_version.metric_dependencies` 冻结 `demo.user.risk.score` 当前 ACTIVE 版本 |
| 7 评估(score>80) | `ruleHit=true` + `finalDecision={code:REJECT,name:"拒绝",priority:1}`;`node_trace` 的 metric 节点 `value_source=FETCHED` |
| 7 评估(取数失败) | metric 节点 `error_code` 落 `MetricFetchError` 细码(如 `TIMEOUT`/`UPSTREAM_ERROR`/`UNAUTHORIZED`),引用节点不命中、整树短路继续(D15 降级不变),session 不崩 |
| 8 改 connector | `data`=受影响行数 1;缓存失效后下次评估读新 descriptor(≤60s TTL 或即时 invalidate) |
| 9 connector :test | `data` 为 `FetchTrace`:`renderedRequest`="POST https://risk.internal.example.com/v1/score"、`rawResponse`=原始响应体、`successMatched=true`、`mappedValue`=分数、`errorCode=null`(成功) |
| 10 metric :test | 同上,验证 `params.connector` 解析到 descriptor + `vars.channel` 渲染进 body |

> 上游评分服务需真实可达(或用 stub),否则步骤 7/9/10 的 trace 会落 `UPSTREAM_ERROR`/`TIMEOUT`——这恰好验证错误归一链路。无真实上游时,可用 `rule-eval-svc` 测试下 `com.sstlfsj.rule.conformance` 的嵌入式 mock 上游跑黄金用例集对照(见 `ConformanceSuiteTest`)。

## 六、清理(恢复干净基线)

```bash
# 删规则(仅删从未发布则级联;已发布只能 disable)
curl -s $H -X DELETE "$BASE/admin/v1/rules/$RID?tenantId=9001"
# 禁用 metric / connector / scene / decision(按需;管理接口提供 status 切换)
curl -s $H -X PUT "$BASE/admin/v1/metrics/demo.user.risk.score/status?tenantId=9001&enable=false"
```

> 评估痕迹(`evaluation_session`/`node_trace`)按 TTL 自动退休,无需手删。infra 的 `engine.rule.fetch` 配置项验证完按需移除。

## 七、相关契约 / 决策

- 决策:[`../../00-decisions.md`](../../00-decisions.md) D72(连接器标准化)、D15(取数失败降级)、D54(metric tenant 级共享)、D60(引擎纯决策化,无 action)。
- 连接器契约(C1–C5 带编号 Requirement):[`../../04-extension.md`](../../04-extension.md) §4.5。
- 存储:[`../../05-storage.md`](../../05-storage.md) `connector_definition` 表(V1_34)、`metric_definition.params` 形态。
- 概念:[`../../01-concepts.md`](../../01-concepts.md) §3.9 sourceType 对比表(EXTERNAL_HTTP 行)。
- 实现:`DeclarativeHttpConnectorHandler` / `ConnectorDefinitionResolver` / `OAuth2TokenManager` / `MetricFetchErrorMapper`;可执行规约 `rule-eval-svc` 测试下 `com.sstlfsj.rule.conformance` 的 `ConformanceSuite`。
