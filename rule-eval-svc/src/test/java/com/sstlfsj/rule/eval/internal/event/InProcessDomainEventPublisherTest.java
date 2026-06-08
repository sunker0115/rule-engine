package com.sstlfsj.rule.eval.internal.event;

import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import static org.mockito.Mockito.*;

class InProcessDomainEventPublisherTest {

    private record SampleEvent() implements DomainEvent {
        public Durability durability() { return Durability.BEST_EFFORT; }
    }

    @Test
    void publish_delegatesToApplicationEventPublisher() {
        ApplicationEventPublisher spring = mock(ApplicationEventPublisher.class);
        DomainEventPublisher pub = new InProcessDomainEventPublisher(spring);
        SampleEvent e = new SampleEvent();

        pub.publish(e);

        verify(spring).publishEvent(e);
    }
}
