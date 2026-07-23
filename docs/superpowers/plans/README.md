# 实现计划索引

**维护约定**：`plans/` 只放**在办**的计划。计划落地后移入 [`archive/`](./archive/)（历史记录，git 保留）。未来要做但未开工的能力见 [`backlog.md`](./backlog.md)。设计真相在 `docs/00`–`10` 设计文档 + 代码，plan 只是脚手架。

状态：`🔲` 待实现 · `⏳` 进行中 · `📐` 设计稿。

## 在办

| 计划文件 | 说明 | 状态 |
|---------|------|------|
| [realtime-streaming-risk-control-design](../specs/2026-06-17-realtime-streaming-risk-control-design.md) | 实时流式风控完整设计（Kafka + Flink + Redis 特征库 + STREAM MetricSource）。**设计稿在 `specs/`**（与 d5c-cep-design 同处）；承载 backlog B8 CEP + B29 物化特征 + 08-evo §2.24 | 📐 |
| [d5c-cep](2026-06-03-d5c-cep.md) | D5-C / B8：CEP 复杂事件处理——**已被上面的流式风控设计覆盖取代**，落地时归并并归档，backlog B8 指向新设计 | 🔲 superseded |

## 已完成（归档）

88 个已落地的实现计划在 [`archive/`](./archive/)，仅留历史，不再逐条索引（git + 文件夹即记录）。其设计已并入 `docs/00`–`10` 设计文档 + 代码。最早的 [docs-completion](../../archive/2026-05-31-docs-completion.md) 与 modulith 架构设计在 `docs/archive/`。

## 未来队列

见 [`backlog.md`](./backlog.md)——按执行性质分组（主动推进序列 / 大件能力 / 触发式 / v3 远期）。
