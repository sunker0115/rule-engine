package com.sstlfsj.rule.eval.internal.metric.fetch;

import com.sstlfsj.rule.kernel.api.model.MetricFetchError;
import org.junit.jupiter.api.Test;
import java.net.http.HttpTimeoutException;
import static org.assertj.core.api.Assertions.assertThat;

class MetricFetchErrorMapperTest {

    private final MetricFetchErrorMapper mapper = new MetricFetchErrorMapper();

    @Test
    void timeoutExceptionMapsToTimeout() {
        assertThat(mapper.fromException(new HttpTimeoutException("t"))).isEqualTo(MetricFetchError.TIMEOUT);
    }

    @Test
    void genericExceptionMapsToUpstreamError() {
        assertThat(mapper.fromException(new RuntimeException("x"))).isEqualTo(MetricFetchError.UPSTREAM_ERROR);
    }

    @Test
    void httpStatusNon2xxMapsToUpstreamError() {
        assertThat(mapper.fromHttpStatus(503)).isEqualTo(MetricFetchError.UPSTREAM_ERROR);
        assertThat(mapper.fromHttpStatus(401)).isEqualTo(MetricFetchError.UNAUTHORIZED);
    }
}
