package com.sstlfsj.rule.bridge;

import com.sstlfsj.rule.bridge.model.SuspectPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * 消费 rt.suspect.customer → 调引擎评估 → 发 rt.decision。
 * 失败 try-catch 吞掉 + SLF4J 告警，正常返回让 offset 提交（失败即丢弃不重投，靠 Flink 覆盖写补偿）。
 */
@Component
public class SuspectConsumer {

    private static final Logger log = LoggerFactory.getLogger(SuspectConsumer.class);

    private final EvalClient evalClient;
    private final DecisionPublisher decisionPublisher;

    public SuspectConsumer(EvalClient evalClient, DecisionPublisher decisionPublisher) {
        this.evalClient = evalClient;
        this.decisionPublisher = decisionPublisher;
    }

    @KafkaListener(topics = "${bridge.suspect-topic:rt.suspect.customer}")
    public void onSuspect(SuspectPayload payload) {
        try {
            String decision = evalClient.evaluate(payload);
            if (decision != null) {
                decisionPublisher.publish(payload.customerId(), "{\"customerId\":\"" + payload.customerId()
                        + "\",\"decision\":\"" + decision + "\"}");
            }
        } catch (Exception e) {
            // 失败即丢弃不重投：offset 正常提交，靠 Flink 侧覆盖写下一轮该客户特征更新后重发 suspect
            log.error("Eval failed for customer={}, suspectId={}, dropped",
                    payload.customerId(), payload.suspectId(), e);
        }
    }
}
