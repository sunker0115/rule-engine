# 开源 CI 本地完善实现计划

> 执行方式：在当前任务内逐项实现，完成后使用 requesting-code-review 独立审查。用户已确认本地完善范围，GitHub 实际执行留到最终发布后。

**目标：** 将已在本地通过的完整验证接入 CI，阻止必要数据库测试漏跑或跳过。

**架构：** 复用已有 Surefire、examples Failsafe 和 Testcontainers；新增只读 XML 校验脚本，不引入 Python 第三方依赖。不改变默认 Maven profile、业务 API 或数据库。

**技术栈：** GitHub Actions、Maven、Python 标准库 unittest / ElementTree。

**设计与契约：** 见 [design](../../../openspec/changes/open-source-verification/design.md) 和 [spec](../../../openspec/changes/open-source-verification/specs/release-verification/spec.md)。本计划是 [tasks](../../../openspec/changes/open-source-verification/tasks.md) 的执行索引。

## 依据

- 现有 `rule-app/pom.xml` 的 examples profile 使用 `**/*Scenario.java`；classesDirectory 修复已通过本地验证。
- 已检查既有数据库测试的真实 Surefire/Failsafe XML：根为 testsuite，四个计数属性齐全，testcase 数与 tests 一致。MySQL-only 收尾后门禁为八类，清单以 scripts/verify_database_tests.py 为准。
- fibra 与 disruptor-spring-boot 均通过 workflow 执行 `clean verify` 并固定 Action 提交；本项目沿用这些约定，不复制其无关制品发布配置。
- 完整 Maven 命令已在本地通过：29 模块、2474 测试；GitHub runner 的实际结果尚未验证。

## 任务 1：测试驱动报告校验

文件：新增 `scripts/verify_database_tests.py` 和 `scripts/tests/test_verify_database_tests.py`。

- [x] 测试创建每类必需 suite 的临时 XML，使用真实脚本 CLI（`--root` 指向临时目录）。正常报告期望退出码 0；缺失、零执行、跳过、失败、错误、无效计数、损坏 XML 和错误 suite 名期望退出码 1，诊断包含测试类。
- [x] 执行 `python3 -B -m unittest discover -s scripts/tests -p 'test_*.py' -v`，记录脚本未实现造成的失败。
- [x] 实现 `check_reports(root: Path) -> list[str]`：按 design D2 的模块、报告目录、suite 名精确查找；读取并验证计数，不把缺失计数当零。校验 testcase 数和子节点状态，避免摘要与内容矛盾被当作通过。
- [x] CLI 默认仓库根目录，支持 `--root` 供测试使用；聚合全部错误输出后返回 1，正常输出必要报告数量及通过结论并返回 0。
- [x] 重跑上述测试，再执行 `python3 -B scripts/verify_database_tests.py` 检查已有真实报告。

## 任务 2：接入 CI 与贡献指南

文件：修改 `.github/workflows/ci.yml`、`CONTRIBUTING.md`。

- [x] Maven 前执行 `docker info` 和报告校验器自身测试。
- [x] Maven 命令替换为已验证的入口：

```sh
mvn --batch-mode --no-transfer-progress clean verify -Pexamples \
  '-Dsurefire.includes=**/Test*.java,**/*Test.java,**/*Tests.java,**/*TestCase.java,**/*IT.java'
```

- [x] Maven 后使用 `if: always()` 执行报告校验；不得使用 continue-on-error。
- [x] 使用核实过的固定 SHA 的 upload-artifact，`if: always()` 保存 `**/target/surefire-reports/**` 与 `**/target/failsafe-reports/**`，保留 7 天；不上传数据目录或凭据文件。
- [x] 贡献指南同步 Node 24、Python 3、完整命令及临时 MySQL 隔离边界；不暗示云端已执行。

## 任务 3：验证与审查

- [x] 执行校验器全部测试和真实报告检查。
- [x] YAML 解析并检查 workflow 的 Docker 预检、完整 Maven 入口、always 报告检查/上传及 SHA 固定。
- [x] 按 mvn-env 再执行完整 clean verify（本地显式 Mockito agent 仅用于本机），随后运行正式报告校验脚本。
- [x] 独立审查本次脚本、测试、workflow 与贡献指南；修复本批缺陷。
- [x] 核对本变更 proposal/design/spec/tasks/plan 与贡献指南内容一致；`openspec validate open-source-verification --strict` 和 `git diff --check` 通过。
- [x] 记录本地结果，不提交、不推送、不将 GitHub 验证标为完成。用户已取消 H2，MySQL-only 收尾及其余发布事项按[轻量计划](../../../.planning/open-source-closeout/task_plan.md)执行，不另设 OpenSpec 确认环节。
