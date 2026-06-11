package com.sstlfsj.rule.eval.internal.event;

/** 领域事件唯一发布缝:进程内 / MQ 各一实现,发布方与 persister 不感知 transport。 */
public interface DomainEventPublisher {
    void publish(DomainEvent event);
}
