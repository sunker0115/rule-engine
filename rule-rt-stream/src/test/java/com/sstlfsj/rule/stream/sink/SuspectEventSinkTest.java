package com.sstlfsj.rule.stream.sink;

import com.sstlfsj.rule.stream.model.SuspectEvent;
import org.apache.flink.connector.kafka.sink.KafkaSink;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class SuspectEventSinkTest {

    @Test
    void create_returnsKafkaSink() {
        assertThatCode(() -> {
            KafkaSink<SuspectEvent> sink = SuspectEventSink.create("localhost:9092", "rt.suspect.customer");
            assertThat(sink).isNotNull();
        }).doesNotThrowAnyException();
    }
}
