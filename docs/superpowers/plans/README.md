# 实现计划索引

按执行顺序排列。`✅` = 已完成，`🔲` = 待实现，`⏳` = 部分完成。

| # | 计划文件 | 说明 | 状态 |
|---|---------|------|------|
| 01 | [docs-completion](2026-05-31-docs-completion.md) | 文档补全（概念/运行时/扩展/存储/可运维/前端/演进/API 契约） | ✅ |
| 02 | [backend-skeleton](2026-06-02-backend-skeleton.md) | Maven 多模块骨架 + SPI 接口 + InterpretedExecutor + ArchUnit | ✅ |
| 03 | [config-layer](2026-06-02-config-layer.md) | rule-config-svc：Flyway 建表 + Mapper + PublishService（DRAFT→PUBLISHED） | ✅ |
| 04 | [eval-layer](2026-06-02-eval-layer.md) | rule-eval-svc：索引加载 + EvalContextAssembler + EvalServiceImpl | ✅ |
| 05 | [e2e-testcontainers](2026-06-02-e2e-testcontainers.md) | Testcontainers E2E 集成测试（PUSH/PULL/dry-run 全链路） | ✅ |
| 06 | [eval-chain-completion](2026-06-03-eval-chain-completion.md) | 评估链路补全：DryRunTraceWriter + ActionDispatchService + ActionHandler | ✅ |
| 07 | [handler-dryrun-and-rollout-ab](2026-06-03-handler-dryrun-and-rollout-ab.md) | BlockTransactionHandler/SendAlertHandler dry-run + RolloutPreGate A/B 灰度 | ✅ |
| 08 | [api-layer](2026-06-03-api-layer.md) | rule-api：GlobalExceptionHandler + DTO + RuleController + ApiAutoConfiguration | ✅ |
| 09 | [rule-list-query-api](2026-06-03-rule-list-query-api.md) | GET /api/v1/rules 规则列表分页查询 | ✅ |
| 10 | [create-draft-api](2026-06-03-create-draft-api.md) | POST /api/v1/rules 创建规则草稿 | ✅ |
| 11 | [audit-query-api](2026-06-03-audit-query-api.md) | GET /api/v1/audit/sessions 审计查询 + trace 端点 | ✅ |
| 12 | [v2-phase2](2026-06-03-v2-phase2.md) | v2 第二阶段：provided-metrics API / node_trace 树重建 / docker-compose / CompiledExecutor（批次 D 待做） | ⏳ |
| 13 | [d12-scorecard-evaluator](2026-06-03-d12-scorecard-evaluator.md) | D12：SCORECARD evaluator（权重打分 + score 字段 + 发布校验） | ✅ |
| 14 | [d13-payload-schema](2026-06-03-d13-payload-schema.md) | D13：payloadSchema 白名单校验（eventType 字段约束） | ✅ |
| 15 | [d20-embedded-sdk](2026-06-03-d20-embedded-sdk.md) | D20：Embedded SDK（EvalEngine 下沉 + rule-sdk + Spring Boot Starter） | ✅ |
| 16 | [d5c-cep](2026-06-03-d5c-cep.md) | D5-C：CEP 复杂事件处理（Flink + 频率/序列/聚合三种模式） | 🔲 |
| 17 | [d34-local-sdk](2026-06-04-d34-local-sdk.md) | D34：嵌入式 SDK 本地模式（代码定义规则，零网络） | ✅ |
