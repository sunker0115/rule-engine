# 统一 MySQL 运行与 Docker 数据库验证

> 非执行门禁：用户明确本项采用 superpowers 轻量流程。本提案仅保留范围核对草稿，实施和验收状态以[当前计划](../../../.planning/open-source-closeout/task_plan.md)为准，不再等待 OpenSpec 确认。

## Why

用户已明确不再使用 H2；本地与 GitHub Actions 均具备通过 Docker 验证真实 MySQL 的路径。当前工作树仍有 H2 运行依赖、默认配置、启动测试和 CI 镜像烟测，必须协调移除，不能只改说明或删除测试来取得通过。

## What Changes

- 删除 rule-config-svc 的 H2 runtime 依赖，默认主库与命名取数数据源恢复 MySQL；保留 mysql profile 对部署凭据的显式要求。
- 用 MySQL Testcontainers 的启动测试替换 EmbeddedDatabaseStartupTest，继续核验 V1、16 张业务表、SYSTEM 租户和默认整体/数据库健康状态。不得连接本机默认业务库。
- 将新启动测试纳入数据库报告门禁；原七类数据库/业务场景测试继续执行，不降低报告完整性要求。
- CI 镜像启动检查改用独立临时 MySQL，与应用容器置于本次专用网络；校验健康状态和 SYSTEM 租户，成功或失败均回收本次资源并保留诊断。
- 同步 README、CONTRIBUTING、CHANGELOG、当前设计文档及规划状态，撤回“默认 H2”和“H2 兼容待办”。append-only 决策日志追加新决策，保留历史正文。

## Impact

- 依赖：rule-config-svc/pom.xml，仅移除 H2，不升级其他依赖。
- 配置：rule-app 的 application.yml、application-local.yml；rule-config-svc 的 application.yml。保留 application-mysql.yml 的部署入口。
- 删除/替换：rule-app/src/test/java/com/sstlfsj/rule/EmbeddedDatabaseStartupTest.java，由同目录 MySqlDatabaseStartupTest.java 接替其断言。
- CI 与验证：.github/workflows/ci.yml、scripts/verify_database_tests.py 及其测试；新增可本地复用的 scripts/verify_mysql_image.sh。
- 文档：README.md、CONTRIBUTING.md、CHANGELOG.md、docs/00-decisions.md、docs/05-storage.md、docs/08-evolution.md、docs/09-skeleton.md、docs/README.md，以及前批 OpenSpec/规划中的当前状态。
- 不改变 MySQL 业务 SQL、JSON 存储格式、公开 API、Flyway V1 业务表结构或依赖版本。不删除已有数据库文件，不操作原库的业务表、数据或 flyway_schema_history。
- 数据库集成测试需要 Docker；不再提供无数据库环境的服务启动承诺。纯内核和不涉及数据库的单测不新增 Docker 依赖。

## 本批不处理

强类型 JSON 规范整改仍单独处理，但不再以 H2 兼容作为理由；XXL 许可、历史开发文件删除、原库 Flyway 历史切换与 GitHub 仓库发布设置不混入本批。

## 状态与关联

用户已明确 MySQL-only 方向及轻量实施流程，已开始实施；本草稿不再维护执行勾选或承担确认环节。

- [实现决策](design.md)
- [行为契约](specs/mysql-verification/spec.md)
- [任务清单](tasks.md)
- [总体收尾计划](../../../.planning/open-source-closeout/task_plan.md)
