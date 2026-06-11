# 案例：风控命中 → 创建人工审核工单（命令式 SPI）

> 🛑 **已过期（2026-06-09）· 当前实现下跑不通** — 使用了废弃形态（`scene.json` 内嵌 `metricBindings`/`decisions`、条件用 `METRIC_COMPARE`/`PAYLOAD_COMPARE`、mock 用 `_mockMetrics` 等），与当前 API 不符，待按新范式重写。
> 👉 可跑通的当前样板：[`../../risk-control/high-risk-login/`](../../risk-control/high-risk-login/)（过期点逐项对照见其 README §五）。

## 业务背景

转账评估命中 REVIEW 决策时，需要在工单系统创建一张人工审核单，由风控人员处理。

工单创建逻辑相对复杂：
1. 按 `eventId` 幂等——同一事件不能重复建单
2. 字段映射较多（主体信息、命中规则、金额、收款方）
3. 需要补偿入口——审核通过后关闭工单

因此使用**命令式 SPI**（`@ActionType("ticket.create")`），不用 `webhook.call`。

## 关键配置

| 维度 | 值 |
|------|----|
| Scene | `risk.transfer`（PULL 模式，同步返回） |
| Decision | REJECT(1) 不建单 / REVIEW(2) 建单 / PASS(100) 不建单 |
| actionType | `ticket.create`（命令式 SPI，业务方实现） |
| 补偿 | `ticket.close`（审核通过后由补偿流水线调用） |
| 幂等键 | `(tenantId, eventId, decisionCode, actionId)`（D27） |

## ActionHandler 实现要点

```java
@ActionType(
    value = "ticket.create",
    timeoutMs = 3000,
    retryable = true
)
public class TicketCreateHandler implements ActionHandler {

    @Override
    public ActionResult execute(Action action, EvalContext ctx) {
        String eventId = ctx.getEvent().getEventId();

        // 幂等：同一 eventId 已建单则跳过
        if (ticketClient.existsByEventId(eventId)) {
            return ActionResult.success();
        }

        ticketClient.create(TicketRequest.builder()
            .title(action.getParam("title"))
            .priority(action.getParam("priority"))
            .assignee(action.getParam("assignee"))
            .metadata(Map.of(
                "eventId",    eventId,
                "subjectId",  ctx.getEvent().getSubjectId(),
                "decision",   action.getParam("decisionCode"),
                "occurredAt", ctx.getEvent().getOccurredAt().toString()
            ))
            .build());

        return ActionResult.success();
    }

    @Override
    public ActionResult compensate(Action action, EvalContext ctx) {
        // 补偿：关闭对应工单（审核通过 / 误判撤回时调用）
        ticketClient.closeByEventId(ctx.getEvent().getEventId());
        return ActionResult.success();
    }
}
```

## 评估流程

```
transfer.initiated 事件进入
        │
        ▼
AST 求值（复用 new-account-large-transfer 的条件树）
        │
  命中  │  不命中
        │
        ▼
finalDecision = REVIEW
        │
        ▼
异步派发 REVIEW.actions
  → ticket.create Handler 执行
  → 按 eventId 幂等建单
  → 返回 ActionResult.success()
        │
        ▼（后续，补偿流水线）
审核通过 → ActionHandler.compensate() → ticket.close
```

## 目录

```
ticket-creation/
├── README.md
├── scene.json
├── metrics/
│   └── metrics.json
├── rules/
│   └── rule-transfer-review.json
├── mock-events.json
└── expected-results.json
```

## 相关决策

- D4 声明式优先 + SPI 兜底：`ticket.create` 属于命令式 SPI，逻辑复杂无法纯声明式表达
- D18 Action 失败补偿语义：`compensateActionType: ticket.close`，补偿由外部流水线调用，不自动触发
- D27 Action 归属 Decision：`ticket.create` 挂在 `REVIEW` Decision 上，`REJECT`/`PASS` 不建单
- D27 幂等键：`(tenantId, eventId, decisionCode, actionId)` 保证同一事件同一决策只建一张单
