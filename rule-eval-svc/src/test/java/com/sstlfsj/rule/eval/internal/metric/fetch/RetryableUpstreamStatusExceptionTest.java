package com.sstlfsj.rule.eval.internal.metric.fetch;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RetryableUpstreamStatusExceptionTest {

    @Test
    void carriesStatusAndMessage() {
        RetryableUpstreamStatusException ex = new RetryableUpstreamStatusException(503);
        assertThat(ex.status()).isEqualTo(503);
        assertThat(ex.getMessage()).contains("503");
    }
}
