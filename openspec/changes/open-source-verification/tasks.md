## 1. 方案确认与基线

- [x] 1.1 核对 CI、Maven 插件、七类数据库测试及隔离方式。
- [x] 1.2 手动运行此前漏掉的 DecisionDefinitionRoundTripIT，确认 1 个用例通过且未跳过。
- [x] 1.3 完成现有 examples 验证并记录真实结果：三个场景因 Failsafe 类路径配置缺失报错。
- [x] 1.4 用户确认本地完善范围，按 writing-plans 记录[实现计划](../../../docs/superpowers/plans/2026-08-30-open-source-verification.md)；云端执行留到最终发布后验证。

## 2. 实施

- [x] 2.1 为报告校验编写失败测试，再实现标准库脚本；12 个 CLI 回归测试通过。
- [x] 2.2a 为 rule-app 的 examples Failsafe 设置编译类目录，修复已复现的启动失败；13 个关联模块及三个场景共 4 个用例通过。
- [x] 2.2 CI 增加 Docker 预检、完整测试匹配与 examples profile。
- [x] 2.3 CI 配置 always 报告核验和上传，保留 7 天；云端实际执行留到发布后。
- [x] 2.4 贡献指南同步完整命令及临时数据库安全边界。

## 3. 收口

- [x] 3.1 正式脚本接入后重跑全量 clean verify：2474 个测试，失败、错误、跳过均为零；七类数据库报告核验通过。
- [x] 3.2 完成本批代码独立审查，无阻塞问题；相关文档同步本地完成与云端待验证边界。
- [x] 3.3 更新 .planning/open-source-closeout 的完成与延期状态，不把未验证事项标为完成。
- [ ] 3.4 本批通过后统一进入延期队列。
