# 开源验证设计

## 现状依据

- pom.xml 的 pluginManagement 仅固定 Surefire/Failsafe 版本，没有默认 *IT 执行绑定。
- rule-app/pom.xml 的 examples profile 已配置 Failsafe integration-test / verify，匹配 *Scenario.java。
- 四个 MySQL 测试使用 @Testcontainers(disabledWithoutDocker=true)，Java 侧容器探测失败也可能跳过。
- ScenarioSupport 通过动态属性把主数据源和 business-db 指向临时容器，并在临时库执行清理。
- 三个 Scenario 曾报 Failed to find merged annotation；Failsafe 报告显示类路径指向重打包 jar，应用类位于 BOOT-INF/classes。按 Spring Boot 官方的 Using Failsafe Without Spring Boot’s Parent POM 配置 classesDirectory 后，已通过本地全量验证。

## D1：复用现有测试入口

CI 执行以下命令，不修改默认开发者生命周期：

```sh
mvn --batch-mode --no-transfer-progress clean verify -Pexamples \
  '-Dsurefire.includes=**/Test*.java,**/*Test.java,**/*Tests.java,**/*TestCase.java,**/*IT.java'
```

架构上，相比为 CI 引入 H2 方言适配，真实 MySQL 保留生产 JSON/SQL 语义；相比单独配置 GitHub services 数据库，复用 Testcontainers 不产生第二套端口和凭据注入路径。运行成本是需要 Docker 和镜像拉取时间，当前项目已有相应依赖及测试结构。

rule-app 的 examples profile 中为 Failsafe 配置：

```xml
<classesDirectory>${project.build.outputDirectory}</classesDirectory>
```

这是恢复不继承 Spring Boot parent 后缺失的测试装配配置，不改变打包产物、测试内容或应用行为。依据：https://docs.spring.io/spring-boot/maven-plugin/integration-tests.html 。

## D2：报告决定是否真的执行

增加 Python 标准库 XML 核验脚本，无新第三方依赖。检查以下八个 suite 的报告：

1. com.sstlfsj.rule.config.integration.DecisionDefinitionRoundTripIT
2. com.sstlfsj.rule.eval.integration.EvalIntegrationTest
3. com.sstlfsj.rule.eval.integration.OutcomeIngestionIntegrationTest
4. com.sstlfsj.rule.job.integration.ScheduledTaskAnnotationIntegrationTest
5. com.sstlfsj.rule.example.scenario.OrderFraudScenario
6. com.sstlfsj.rule.example.scenario.CreditEvaluationScenario
7. com.sstlfsj.rule.example.scenario.SdkTradingScenario
8. com.sstlfsj.rule.MySqlDatabaseStartupTest

每份报告必须存在、suite 名称匹配、tests 大于零，且 errors/failures/skipped 均为零。testcase 数须与 tests 一致，子节点不得包含失败、错误或跳过，避免摘要与实际内容矛盾。缺失、格式损坏、计数异常均返回非零退出码，并指出测试类和原因。clean 消除旧报告误通过；脚本只读报告，不操作数据库。

## D3：保留故障证据

CI 始终上传 Surefire/Failsafe 报告。Docker 预检在 Maven 之前执行；Maven 失败仍保留报告，但不把报告上传成功当作测试通过。

## D4：不混入运行时改动

最初 CI 完善不修改默认数据库。用户随后明确取消 H2，默认配置与启动测试已在 MySQL-only 轻量收尾中统一为 MySQL；镜像烟测也使用临时 MySQL。该收尾不修改业务 SQL、V1 表结构或公开 API，整体发行仍需另行验收。

Mockito self-attach 曾在本地受限环境失败，本地验证使用现有 Mockito jar 的显式 agent；不能据此推断 GitHub 必然失败，也不在本批无依据升级依赖。

## 验证方法

- 报告校验器先写失败测试，覆盖缺失报告、零执行、跳过、失败、错误、损坏 XML、正常七类报告。
- 完整执行上述 Maven 命令及报告核验。
- 检查临时容器生命周期和数据源注入，不引入外部业务库 URL。
- GitHub 上的运行结论待仓库远端和 workflow 运行结果可用后再给出。

方案与范围见 [proposal](proposal.md)，行为契约见 [spec](specs/release-verification/spec.md)，执行索引见 [tasks](tasks.md)，实施步骤见[实现计划](../../../docs/superpowers/plans/2026-08-30-open-source-verification.md)。
