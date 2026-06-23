package com.sstlfsj.rule.bridge;

import com.sstlfsj.rule.bridge.model.RtDecision;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class DecisionPublisherTest {

    @Test
    @SuppressWarnings("unchecked")
    void serializesAndPublishesToConfiguredTopicKeyedByCustomerId() {
        KafkaTemplate<String, String> template = mock(KafkaTemplate.class);
        ObjectMapper mapper = JsonMapper.builder().build();
        DecisionPublisher publisher = new DecisionPublisher(template, mapper, "rt.decision");

        RtDecision d = new RtDecision("c1", "HIGH", "c1-100", Instant.parse("2026-06-20T07:00:00Z"));
        publisher.publish(d);

        // 验 typed 序列化（非手拼），key=customerId
        String expectedJson = mapper.writeValueAsString(d);
        verify(template).send("rt.decision", "c1", expectedJson);
    }
}
