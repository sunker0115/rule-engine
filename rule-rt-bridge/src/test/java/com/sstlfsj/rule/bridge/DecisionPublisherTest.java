package com.sstlfsj.rule.bridge;

import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class DecisionPublisherTest {

    @Test
    @SuppressWarnings("unchecked")
    void publishesToConfiguredTopicKeyedByCustomerId() {
        KafkaTemplate<String, String> template = mock(KafkaTemplate.class);
        DecisionPublisher publisher = new DecisionPublisher(template, "rt.decision");

        publisher.publish("c1", "{\"decision\":\"HIGH\"}");

        verify(template).send("rt.decision", "c1", "{\"decision\":\"HIGH\"}");
    }
}
