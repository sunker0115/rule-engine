# rule-samples — 接入姿势示例库

面向**接入方开发者**的"使用指南即代码":每种接入姿势一个能跑的最小 demo,可直接复制进自己工程。
与 [`docs/examples/`](../docs/examples/)(声明式配置 + curl)、`rule-app/src/test` 端到端测试(验正确性)互补——本模块是 **Java 接入代码**:接入姿势 demo 用裸 `main()` 跑、打印结果(不带断言);注解特性示例则配端到端 IT 断言(在 CI 执行),作可运行参考。

## 四种接入姿势怎么选

| 姿势 | demo | 连服务端? | 适合谁 |
|---|---|---|---|
| HTTP 远程 | `httpclient/HttpClientDemo` | 是 | 跨语言 / 规则集中服务端 / 不嵌 SDK |
| SDK 轮询嵌入 | `sdkpolling/SdkPollingDemo` | 是 | Java 接入,要本地低延迟评估 + 服务端集中管理 |
| SDK 本地 JSON | `sdklocal/SdkLocalDemo` | 否 | 离线 / 边缘,规则随应用发布 |
| 注解规则即代码(Easy Rules 风格) | `annotation/AnnotationDemoApplication` | 否 | 规则随代码演进,要强类型 + 重构友好;`@Condition` 布尔方法写条件、`@OnDecision`/`@EventListener` 接动作 |

## 注解规则:判定原语与消费方式

注解姿势(`annotation` 包)下,一条 `@RuleDef` 规则用**三选一的判定原语**写逻辑;命中的决策可被**推/拉**两种方式消费。各示例都配端到端 IT 作可运行参考:

| 原语 / 能力 | 样例 | 说明 |
|---|---|---|
| `@Condition` 布尔条件 + 动作 | `annotation/LargeTradeRule` | 命中后甲 `@EventListener` / 乙 `@OnDecision` 双动作路径(`AnnotationDemoIT`) |
| `@Fact` 嵌套路径 | `annotation/NestedOrderRule` | `@Fact("order.amount")` 下钻 payload(`NestedOrderRuleIT`) |
| `@Score` + `@ScoreBand` | `annotation/CreditScoreRule` | 返回分 → 阈值分档映射决策,带回 `EvalResult.score`(`NonBooleanRuleIT`) |
| `@Decide` | `annotation/RiskDecideRule` | Java 多分支直接产出决策码,返回 `List` 可一次多决策(`NonBooleanRuleIT`) |
| `@Metric` 取数注入 + `@MetricSource` 供给 | `metric/VelocityRule` + `metric/VelocityMetrics` | 消费侧 `@Metric` 声明依赖+注入;供给侧 `@MetricSource` 一个方法即"取数逻辑+定义"(免实现接口、免写 descriptor)(`VelocityRuleIT`) |
| 一 handler 多 metric(接口式,配置驱动) | `metric/featurestore/*` | 一个 `MetricSourceHandler` 按 `metricCode` 服务多个 metric(共享后端、加特征只加定义不改码);适合 SQL/HTTP/特征库这类形态(`FeatureStoreIT`) |
| 消费·拉(业务方法读结果) | `service/OrderService` | `@Service` 注入 client → evaluate → 按决策码走业务分支(`OrderServiceIT`) |

推 = 规则上挂 `@OnDecision`/`@EventListener`,命中后在评估调用栈内自动跑副作用(慢处理器可加 `@OnDecision(async=true)`);拉 = 业务方法读 `EvalResult` 自己分支。两者可并存。`@OnDecision(fromRuleCode="x")` 可把处理器精确绑到某条规则。

## 特性 demo

按接入姿势之外,单独演示某类规则能力(均零依赖、`main()` 直接跑):

| demo | 演示能力 |
|---|---|
| `timecondition/TimeConditionDemo` | 内置时间条件:`time.occurred_at`(事件时间 BEFORE/AFTER/BETWEEN,确定性)+ `time.window`(营业时段,结果随运行时刻变化)。kernel 默认注册,无需自定义算子。 |
| `metric/MetricDemoApplication` | `@Metric` 取数注入:规则依赖派生指标 `recent_txn_count`,stub handler 模拟取数;同样大额交易,近期交易数高的命中、低的不命中——决策由预拉 metric 驱动。 |

## 运行前提

- 跑 mvn 前先设置 `$MVN`(本机 mvn 不在 PATH)。
- **HTTP / SDK 轮询**两个 demo 需要:
  1. 起 rule-app(用打包产物,别用 reactor run 目标):`java -jar rule-app/target/rule-app-*.jar`
  2. 租户先存在(当前无租户创建 API)。默认 id 9100 / code samples(9100 取高位避开低段真实租户),执行一次:
     ```sql
     INSERT INTO tenant (id, code, name) VALUES (9100, 'samples', '示例租户');
     ```
     **若 9100 也被占用**:seed 另一个空闲 id,运行时用系统属性指向它,无需改代码:
     ```bash
     $MVN -pl rule-samples exec:java \
       -Dexec.mainClass="com.sstlfsj.rule.samples.httpclient.HttpClientDemo" \
       -Ddemo.tenantId=<你的 id>
     ```
     覆盖优先级:`-Ddemo.tenantId` / `-Ddemo.tenantCode` > 环境变量 `DEMO_TENANT_ID` / `DEMO_TENANT_CODE` > 默认 9100 / samples。
- **SDK 本地 / 注解**两个 demo:零依赖,直接跑(规则源不连库,租户 id 仅作本地标识,无冲突)。

## 跑各 demo

```bash
$MVN -pl rule-samples exec:java -Dexec.mainClass="com.sstlfsj.rule.samples.sdklocal.SdkLocalDemo"
$MVN -pl rule-samples exec:java -Dexec.mainClass="com.sstlfsj.rule.samples.annotation.AnnotationDemoApplication"
$MVN -pl rule-samples exec:java -Dexec.mainClass="com.sstlfsj.rule.samples.timecondition.TimeConditionDemo"
$MVN -pl rule-samples exec:java -Dexec.mainClass="com.sstlfsj.rule.samples.metric.MetricDemoApplication"
$MVN -pl rule-samples exec:java -Dexec.mainClass="com.sstlfsj.rule.samples.httpclient.HttpClientDemo"
$MVN -pl rule-samples exec:java -Dexec.mainClass="com.sstlfsj.rule.samples.sdkpolling.SdkPollingDemo"
```

## SDK 轮询的 starter 等价写法

`SdkPollingDemo` 用 builder 手动构造。用 starter 时,只需在 `application.yml` 配置,starter 自动装配 `RuleEngineClient` Bean:

```yaml
rule:
  sdk:
    server-url: http://localhost:8080
    tenant-id: "9001"
    poll-interval: 2s
    scenes:
      - merchant-trade
```

注入即用:`@Autowired RuleEngineClient client;`。

## 重复跑 & 清库

- **重复跑无需清库**:`httpclient` / `sdkpolling` 的建配是幂等的——scene / decision / rule 已存在就复用、跳过创建。反复跑不会因"资源已存在"报错。
- **彻底重置**:残留的 `evaluation_session` / `audit_log` 是 D14 不可变审计(无删除 API),只能直连 DB 清。需要回到干净基线时跑一次:
  ```bash
  mysql -uroot -p123456 rule_engine < rule-samples/cleanup.sql
  ```
  默认清 id 9100 的租户数据并保留租户行;改过 `-Ddemo.tenantId` 就同步改 `cleanup.sql` 顶部的 `@tid`。
