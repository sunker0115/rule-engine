# MySQL 单一数据库验证设计

## 当前依据

- rule-config-svc 已有 MySQL Connector/J 与 Testcontainers mysql 依赖；rule-app 已有 Testcontainers/JUnit 集成，无需新增数据库库或模块。
- 默认应用、config 模块及 local 取数配置仍指向 H2；application-mysql.yml 已提供 MySQL 的显式凭据配置。
- EmbeddedDatabaseStartupTest 使用 H2 覆盖数据源，现有两项测试分别校验整体/数据库健康、建表和 SYSTEM 种子。
- 既有 CI 已预检 Docker、执行完整 Maven 验证、核验七类报告，但镜像烟测仍无数据库参数启动容器。

## D1：只支持 MySQL，不建立双方言层

直接使用项目现有 MySQL 技术栈。与继续适配 H2 相比，不引入额外 JSON handler、统计 SQL 分支或双数据库行为差异；与另一种内嵌数据库相比，不重新承担驱动和方言验证成本。代价是完整服务必须连接 MySQL，数据库测试必须有 Docker；这是用户明确选择的运行边界。

默认恢复本地 MySQL 的 URL、root 用户及既有本地测试密码默认值，均可通过环境变量覆盖。保留 mysql profile 的显式部署参数校验，不要求现有 Compose 使用者更换启动方式。所有本轮验证只向临时库注入动态地址与凭据，不实际使用默认本地连接。

## D2：启动回归覆盖默认配置而非特殊测试 profile

MySqlDatabaseStartupTest 只通过 DynamicPropertySource 覆盖临时库 URL、用户名、密码及命名取数数据源。不要设置 mysql profile 或覆盖 driver-class-name，以便真正检查默认 MySQL 驱动与配置是否正确。

复用既有 Testcontainers 模式，容器自行创建空库并管理生命周期，不跳过 Docker 不可用情况。迁移用正式 V1，不加载业务场景的 V900。保留原两项测试意图，额外确认运行时没有 H2 驱动类。

数据库报告检查新增该 suite；仍读取真实 Surefire/Failsafe 报告，不把 Maven 退出 0 或容器可启动视为数据库功能通过。

## D3：独立验证最终镜像

Maven 测试不能覆盖 Dockerfile 中的产物与运行权限。保留镜像烟测，但应用连接本次创建的 MySQL 8.4 容器，与现有 Compose 数据库系列一致。

新脚本接收待验证镜像标识；创建独占名称的网络与数据库/应用容器，MySQL 不映射宿主 3306，应用仅使用动态回环端口。等待真实 SQL 可用后启动应用，轮询 /actuator/health 与 SYSTEM 租户读取；超时必须失败。trap 只回收脚本实际创建的容器、匿名卷及网络，不调用全局 prune、不操作已有容器或数据目录。脚本在本地和 CI 复用。

## D4：文档与历史边界

当前运行指南整段替换 H2 承诺，明确 MySQL 和 Docker 条件。00-decisions 的历史正文不改，追加决策覆盖 D78 的默认 H2 部分；V1 合并与原库保护的决定不撤回。既有索引名和表结构不因移除 H2 反向修改。

历史归档示例中的 H2 描述不构成运行依赖，不批量删除历史文件；当前设计正文不得继续要求双数据库验证。H2 兼容从延期队列撤销，JSON 规范整改仍保留独立范围。

## 验证与参考

- 先验证新的默认 MySQL 配置/启动断言能够发现旧的 H2 默认值，再协调移除依赖和配置。
- 执行全仓 clean verify -Pexamples 并包含 *IT，正式报告门禁要求新增启动 suite 和原七类 suite 均无失败、错误、跳过。
- 检查最终 jar 无 BOOT-INF/lib/h2-*.jar；构建镜像并运行同一 MySQL 烟测脚本。
- 使用临时 MySQL 原样执行 README 场景，核对配置、规则快照、评估、审计和 trace 真落库。
- Testcontainers 参考：https://java.testcontainers.org/ 。项目装配参照 rule-app/src/test/java/com/sstlfsj/rule/example/ScenarioSupport.java 与 rule-config-svc/src/test/java/com/sstlfsj/rule/config/integration/DecisionDefinitionRoundTripIT.java。

本地成功不等于 GitHub Actions 已运行；正式云端验证仍留在最终发布阶段。需求见 [proposal](proposal.md)，验收见 [spec](specs/mysql-verification/spec.md)，进度见 [tasks](tasks.md)。
