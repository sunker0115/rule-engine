## ADDED Requirements

### Requirement: 服务仅使用 MySQL 持久化

服务默认配置及本地 profile SHALL 使用 MySQL，运行时产物 SHALL NOT 包含 H2 驱动。移除 H2 SHALL NOT 改变已有 MySQL 业务表或 JSON 数据格式。

#### Scenario: 使用独立 MySQL 启动

- **WHEN** 提供临时空 MySQL 的连接环境变量并启动默认应用
- **THEN** 无需额外数据库 profile 即可执行正式 V1 并进入健康状态
- **AND** SYSTEM 租户可读取，Redis 默认健康策略不变

#### Scenario: 用户已有数据库

- **WHEN** 执行本批测试、构建与镜像烟测
- **THEN** 不连接、修改或清理用户原始数据库及本地数据库文件

### Requirement: Docker 数据库测试保留全部门禁

验证 SHALL 执行新增 MySQL 启动测试及原七类 MySQL/业务场景测试，报告缺失、跳过、失败、错误或零执行均 SHALL 导致门禁失败。

#### Scenario: 默认配置验证

- **WHEN** MySQL 启动测试注入临时库连接但不覆盖驱动和 profile
- **THEN** 正式迁移生成 16 张业务表和 SYSTEM 租户，数据库与整体健康均为 UP

#### Scenario: Docker 不可用或测试漏跑

- **WHEN** 容器不能启动或任一必要 suite 未实际执行成功
- **THEN** 完整验证失败，不回退 H2 或跳过数据库门禁

### Requirement: 发布镜像使用临时 MySQL 验证

镜像烟测 SHALL 在本地与 CI 复用，验证最终构建镜像连接隔离 MySQL，并仅清理本次创建的资源。

#### Scenario: 镜像就绪

- **WHEN** 独立 MySQL 和应用镜像启动成功
- **THEN** HTTP 健康为 UP 且 SYSTEM 租户读取成功后才通过烟测

#### Scenario: 验证失败

- **WHEN** MySQL 或应用启动失败、HTTP 断言失败或等待超时
- **THEN** 脚本返回非零、输出诊断并回收本次容器和网络，不影响已有资源

### Requirement: 当前文档反映单数据库选择

运行和贡献文档 SHALL 使用 MySQL/Docker 的已确认边界，不再把 H2 支持列为当前能力或待完成的发行条件。

#### Scenario: 读取开源入门文档

- **WHEN** 贡献者按 README 和 CONTRIBUTING 操作
- **THEN** 能找到 MySQL 连接及 Docker 测试要求，且不存在无数据库启动承诺
- **AND** GitHub 实际运行状态与本地验证结果明确区分
