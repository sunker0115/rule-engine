# 开源验证收尾

## Why

开源前必须证明真实 MySQL 集成测试和端到端场景确实执行。完善前 CI 只调用默认 clean verify，漏掉 DecisionDefinitionRoundTripIT 和 examples 场景，Docker 不可用时部分测试还可能跳过。构建成功不足以作为数据库功能通过的依据。

## What Changes

- CI 保持 Ubuntu / Java 25，增加 Docker 预检。
- 保留 Surefire 的默认测试匹配，显式补充 *IT，并开启已有 examples profile。
- 修复 rule-app 的 Failsafe 类路径：使用编译输出目录，而非 Spring Boot 重打包 jar；已实测三个 Scenario 因缺少此配置在启动前失败。
- 增加标准库实现的测试报告核验及其单元测试；要求必要数据库测试类的报告齐全、有测试执行且无失败、错误或跳过。MySQL-only 收尾已将默认启动测试纳入第八类门禁。
- 无论构建成功或失败都保存 Surefire/Failsafe 报告，便于核对实际执行情况。
- 贡献指南补充完整验证命令和测试隔离边界。启动示例按现有 API 核对，默认 MySQL；用户已取消 H2。

## Impact

- 本批修改 .github/workflows/ci.yml、rule-app/pom.xml 中的 examples profile、CONTRIBUTING.md；新增小型报告核验脚本及其测试。
- 不改父 POM、依赖版本、默认本地测试习惯、业务 SQL、数据库结构或公开接口。
- 不连接用户原始数据库；测试只能使用已有 Testcontainers 动态创建的隔离实例。
- GitHub 尚未实际运行，不能以本地结果代替托管 runner 的最终验证。

## 本批不处理

- JSON 强类型端到端整改、公开审计 API 与前端协议变更。
- 开放任务配置、历史快照解析、trace 动态值的表示方式。
- XXL 许可、历史开发文件删除、原始数据库 Flyway 历史切换、仓库发布设置。

本批完成后按上述依赖关系统一推进，不把延期事项视为已完成或不阻塞发行。

用户已明确默认数据库恢复 MySQL，本项按 superpowers 轻量流程收尾，不再研究 H2 兼容或增加 OpenSpec 确认环节。

## 状态与关联

状态：本地 CI 完善已完成。12 个报告校验器测试、全仓 2474 个 Java 测试、七类数据库报告核验及独立代码审查通过。实际 GitHub 执行按用户要求留到最终发布后验证；本状态不表示整个项目已达到发行条件。

- [实现决策](design.md)
- [验收契约](specs/release-verification/spec.md)
- [任务清单](tasks.md)
- [实现计划](../../../docs/superpowers/plans/2026-08-30-open-source-verification.md)
- [分阶段进度](../../../.planning/open-source-closeout/task_plan.md)
