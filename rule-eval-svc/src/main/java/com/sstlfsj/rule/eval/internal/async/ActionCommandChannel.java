package com.sstlfsj.rule.eval.internal.async;

/**
 * action 派发命令的投递契约：触发「去执行这次命中的 action」，由唯一消费者（派发器）处理。
 *
 * <p>这是「命令」而非「领域事件」，与 {@link com.sstlfsj.rule.eval.internal.event.DomainEventPublisher}
 * 正交：后者是事实落库的一对多扇出（多监听器、best-effort），本接口是命令的一对一触发
 * （单一消费者 {@code ActionDispatchService}，语义意图 at-least-once）。
 *
 * <p>本期由 {@link InProcessAsyncCommandChannel}（进程内异步队列）实现，实际为 best-effort（队列满/崩溃可丢）；
 * 真正的 at-least-once 待换 Kafka/AMQP 实现兑现，发布方（评估服务）不动。这是 MQ 的预留缝。
 */
public interface ActionCommandChannel {

    /**
     * 投递一个 action 派发命令，交由唯一消费者执行（语义意图至少一次送达）。
     *
     * @param command 待派发的 action 命令
     */
    void deliver(DispatchActionsCommand command);
}
