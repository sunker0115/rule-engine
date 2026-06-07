package com.sstlfsj.rule.eval.internal.async;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link ActionDeliveryChannel} 的本期实现：发布 Spring Modulith 持久事件（event_publication outbox）。
 *
 * <p>必须在事务内发布——持久事件的发布登记在事务提交时写入 event_publication，
 * {@code @ApplicationModuleListener} 消费者在提交后异步触发；崩溃未完成项重启重投（at-least-once）。
 */
@Component
public class ModulithOutboxDeliveryChannel implements ActionDeliveryChannel {

    private final ApplicationEventPublisher publisher;

    public ModulithOutboxDeliveryChannel(ApplicationEventPublisher publisher) {
        this.publisher = publisher;
    }

    @Override
    @Transactional
    public void deliver(ActionRequested event) {
        publisher.publishEvent(event);
    }
}
