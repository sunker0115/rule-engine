package com.sstlfsj.rule.stream.sink;

import com.sstlfsj.rule.stream.model.SuspectEvent;
import org.apache.flink.connector.kafka.sink.KafkaRecordSerializationSchema;
import org.apache.flink.connector.kafka.sink.KafkaSink;
import org.apache.kafka.clients.producer.ProducerRecord;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;

/** 将 SuspectEvent 序列化 JSON 发到 rt.suspect.customer，key=customerId。 */
public final class SuspectEventSink {
    private SuspectEventSink() {}

    public static KafkaSink<SuspectEvent> create(String brokers, String topic) {
        return KafkaSink.<SuspectEvent>builder()
                .setBootstrapServers(brokers)
                .setRecordSerializer(new SuspectEventSerializer(topic))
                .build();
    }

    private static final class SuspectEventSerializer implements KafkaRecordSerializationSchema<SuspectEvent> {
        private static final JsonMapper MAPPER = JsonMapper.builder().build();
        private final String topic;

        SuspectEventSerializer(String topic) { this.topic = topic; }

        @Override
        public ProducerRecord<byte[], byte[]> serialize(SuspectEvent e, KafkaSinkContext ctx, Long timestamp) {
            try {
                byte[] key = e.customerId.getBytes(StandardCharsets.UTF_8);
                byte[] val = MAPPER.writeValueAsBytes(e);
                return new ProducerRecord<>(topic, key, val);
            } catch (Exception ex) {
                throw new RuntimeException("Failed to serialize SuspectEvent", ex);
            }
        }
    }
}
