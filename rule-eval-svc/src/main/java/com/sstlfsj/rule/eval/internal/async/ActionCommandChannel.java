package com.sstlfsj.rule.eval.internal.async;

/**
 * action 派发命令的投递契约：触发「去执行这次命中的 action」，由唯一消费者（派发器）处理。
 *
 * <p>这是「命令」而非「领域事件」，与 {@link com.sstlfsj.rule.eval.internal.event.DomainEventPublisher}
 * 正交：后者是事实落库的一对多扇出（多监听器），本接口是命令的一对一触发
 * （单一消费者 {@code ActionDispatchService}）。
 *
 * <p>本期由 {@link InProcessAsyncCommandChannel}（进程内异步队列）实现，语义为 best-effort fire-and-forget
 * （队列满/进程重启可丢、不重试、不保证投递）；可靠投递（at-least-once）未来换 Kafka/AMQP 实现兑现，
 * 发布方（评估服务）不动。这是 MQ 的预留缝。
 */
public interface ActionCommandChannel {

    /**
     * 投递一个 action 派发命令，交由唯一消费者执行（best-effort：队列满可丢，不重试）。
     *
     * @param command 待派发的 action 命令
     */
    void deliver(DispatchActionsCommand command);
}
