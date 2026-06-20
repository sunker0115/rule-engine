package com.sstlfsj.rule.bridge;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/** 发决策码到 rt.decision，key=customerId。 */
@Component
public class DecisionPublisher {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final String decisionTopic;

    public DecisionPublisher(KafkaTemplate<String, String> kafkaTemplate,
                             @org.springframework.beans.factory.annotation.Value("${bridge.decision-topic:rt.decision}") String decisionTopic) {
        this.kafkaTemplate = kafkaTemplate;
        this.decisionTopic = decisionTopic;
    }

    /** @param customerId 决策主体（topic key） @param decisionJson 决策 JSON */
    public void publish(String customerId, String decisionJson) {
        kafkaTemplate.send(decisionTopic, customerId, decisionJson);
    }
}
