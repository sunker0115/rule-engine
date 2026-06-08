package com.sstlfsj.rule.eval.internal.event;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/** 进程内实现:转 Spring 应用事件,由各 persister 的 @EventListener 消费。durability 当前为元数据。 */
@Component
public class InProcessDomainEventPublisher implements DomainEventPublisher {

    private final ApplicationEventPublisher publisher;

    public InProcessDomainEventPublisher(ApplicationEventPublisher publisher) {
        this.publisher = publisher;
    }

    @Override
    public void publish(DomainEvent event) {
        publisher.publishEvent(event);
    }
}
