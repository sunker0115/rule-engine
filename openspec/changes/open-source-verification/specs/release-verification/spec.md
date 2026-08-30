## ADDED Requirements

### Requirement: CI 完整执行真实数据库验证

后端 CI SHALL 执行默认单元测试、现有四个 MySQL 集成测试、三个端到端场景及默认 MySQL 启动测试。测试类清单见本变更 design.md D2。

#### Scenario: Docker 环境正常

- **WHEN** 后端 CI 执行完整验证命令
- **THEN** Surefire 执行默认测试和 *IT，Failsafe 执行 examples 场景
- **AND** 数据库使用测试动态创建的临时 MySQL 容器，不连接用户原始数据库

#### Scenario: Docker 环境不可用

- **WHEN** Docker 预检失败，或 Java Testcontainers 导致数据库测试跳过
- **THEN** 后端 CI 不得显示数据库验证通过

### Requirement: 数据库报告必须完整且无跳过

报告核验 SHALL 检查 design.md D2 所列每个 suite 的报告存在、有实际用例执行、零失败、零错误且零跳过。

#### Scenario: 测试被漏跑

- **WHEN** 任一必要 suite 报告不存在或 tests 为零
- **THEN** 校验返回非零退出码并指出缺失或未执行的测试类

#### Scenario: 报告失败或损坏

- **WHEN** 任一必要报告包含失败、错误、跳过、无效计数或不可解析 XML
- **THEN** 校验返回非零退出码并提供对应原因

#### Scenario: 全部必要测试执行成功

- **WHEN** 全部必要报告存在且计数符合通过条件
- **THEN** 报告校验成功

### Requirement: 可追踪验证结果且不扩大运行时范围

CI SHALL 保留测试报告以支持失败诊断。本批 SHALL NOT 改变默认本地测试命令的行为、业务数据库结构、公开 API 或生产数据库连接。

#### Scenario: Maven 执行失败

- **WHEN** Maven 测试或打包失败
- **THEN** 已生成的测试报告仍可作为 CI 产物获取，任务保持失败

#### Scenario: 本地开发默认验证

- **WHEN** 开发者仍执行原有 mvn test 或 mvn verify
- **THEN** 本批不得隐式增加或改变原默认 profile
