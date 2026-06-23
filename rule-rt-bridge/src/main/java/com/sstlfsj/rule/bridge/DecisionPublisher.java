package com.sstlfsj.rule.bridge;

import com.sstlfsj.rule.bridge.model.RtDecision;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** 发决策结果到 rt.decision，key=customerId，由注入的 ObjectMapper typed 序列化（不手拼 JSON）。 */
@Component
public class DecisionPublisher {

    private static final Logger log = LoggerFactory.getLogger(DecisionPublisher.class);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final String decisionTopic;

    public DecisionPublisher(KafkaTemplate<String, String> kafkaTemplate, ObjectMapper objectMapper,
                             @Value("${bridge.decision-topic:rt.decision}") String decisionTopic) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.decisionTopic = decisionTopic;
    }

    /** @param decision 决策结果（typed），序列化为 JSON 发往 rt.decision。 */
    public void publish(RtDecision decision) {
        try {
            String json = objectMapper.writeValueAsString(decision);
            kafkaTemplate.send(decisionTopic, decision.customerId(), json);
        } catch (Exception e) {
            log.error("发布 decision 失败 customerId={}", decision.customerId(), e);
        }
    }
}
