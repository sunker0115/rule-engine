package com.sstlfsj.rule.eval.internal.async;

/**
 * action 派发事件的可靠投递契约（at-least-once）。
 *
 * <p>本期由 {@link ModulithOutboxDeliveryChannel}（Modulith 持久事件 outbox）实现；
 * 下一期可换 Kafka/AMQP 实现，发布方与 {@code ActionDispatcher} 不动。这是 MQ 的预留缝。
 */
public interface ActionDeliveryChannel {

    /**
     * 可靠投递一个 action 派发事件，保证至少一次送达消费者。
     *
     * @param event 待投递的 action 事件
     */
    void deliver(ActionRequested event);
}
