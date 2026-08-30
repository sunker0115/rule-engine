# 贡献指南

感谢你对 Rule Engine 的关注。请让一次贡献只解决一个明确问题，并避免夹带无关重构或格式化。

## 开发环境

- JDK 25
- Maven 3.9+
- Node.js 24 与 npm（仅前端改动需要，与 CI 一致）
- Python 3.9+（运行测试报告校验，无第三方依赖）
- Docker（默认启动测试、MySQL 集成测试和完整示例需要）

开发运行、集成测试与生产部署统一使用 MySQL；测试通过 Docker 创建临时数据库。在仓库根目录执行日常后端测试：

```bash
docker info
mvn --batch-mode --no-transfer-progress clean test
```

前端验证：

```bash
cd frontend
npm ci
npm test
npm run build
```

涉及 MySQL 方言、配置到运行落库链路或数据库结构时，执行与 CI 相同的完整验证：

```bash
docker info
python3 -B -m unittest discover -s scripts/tests -p 'test_*.py' -v
mvn --batch-mode --no-transfer-progress clean verify -Pexamples \
  '-Dsurefire.includes=**/Test*.java,**/*Test.java,**/*Tests.java,**/*TestCase.java,**/*IT.java'
python3 -B scripts/verify_database_tests.py
```

这些数据库测试使用 Testcontainers 创建的临时 MySQL，不需要本机预建数据库或配置业务库凭据。不要把测试数据源改为已有业务数据库；场景测试会清理自己的临时库。

必须保留 `clean`，避免上次报告干扰判断。校验脚本要求八类测试报告齐全：四个 MySQL 集成测试类、三个端到端场景和 `MySqlDatabaseStartupTest`；所有报告必须有实际执行且无失败、错误或跳过，权威清单以 `scripts/verify_database_tests.py` 为准。启动测试不设置测试 profile 或覆盖 JDBC 驱动，验证默认 MySQL 配置在临时库执行 V1 后得到 16 张业务表、`SYSTEM` 租户和 `UP` 健康状态。仅 `docker info` 成功或 Maven 返回成功不足以代替报告核验。

CI 构建 `rule-engine:ci` 镜像后，还使用临时 MySQL 执行镜像烟测；本地已构建同名镜像时可运行同一命令，不连接外部业务库：

```bash
bash scripts/verify_mysql_image.sh rule-engine:ci
```

CI 无论成功或失败都会尝试保存已生成的 Surefire/Failsafe 报告，保留 7 天。本地通过不等于 GitHub Actions 已通过，云端执行结果在仓库发布后另行验证。

## 代码与架构

- `rule-kernel` 保持纯 Java，不依赖 Spring。
- 模块依赖方向、SPI 落点和持久化约束以 `docs/` 为准。
- 修复缺陷时优先增加复现测试；新逻辑和测试应在同一次提交中完成。
- 日志使用 SLF4J，不使用 `System.out` 或 `System.err`。
- 公开接口、SPI、AutoConfiguration 和数据结构必须遵循仓库中的注释与类型边界规范。
- 数据库变更新增顺序 Flyway migration；不要修改已经进入公开版本的 migration。

## 提交问题

提交 Issue 前请先搜索已有问题。缺陷报告至少包含：

- Rule Engine、JDK、Maven、数据库和浏览器版本；
- 最小复现步骤；
- 预期行为与实际行为；
- 必要日志和异常堆栈，移除凭据及业务敏感数据。

安全漏洞不要通过公开 Issue 报告，请遵循 `SECURITY.md`。

## 提交 Pull Request

Pull Request 应说明问题、影响面、主要取舍和验证结果。提交前请确认：

- 相关模块测试与全仓门禁通过；
- 对外行为变化已同步 README、API 或设计文档；
- 没有提交构建产物、IDE 元数据、数据库文件、凭据或私有配置；
- 破坏性变更明确说明迁移方式；
- 一次 Pull Request 不包含无关重构。

提交贡献表示你有权提交相关内容，并同意该贡献按项目根 `LICENSE` 中的 GPL-3.0-only 许可。
