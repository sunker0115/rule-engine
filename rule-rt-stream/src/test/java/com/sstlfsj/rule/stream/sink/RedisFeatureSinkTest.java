package com.sstlfsj.rule.stream.sink;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThatCode;

class RedisFeatureSinkTest {
    @Test
    void construction_doesNotThrow() {
        assertThatCode(() -> new RedisFeatureSink("localhost", 6379)).doesNotThrowAnyException();
    }
}
