package com.sstlfsj.rule.eval.internal.metric.fetch;

import com.sstlfsj.rule.config.api.connector.ResiliencePolicy;
import com.sstlfsj.rule.config.api.connector.RetryTrigger;
import org.junit.jupiter.api.Test;

import java.net.http.HttpTimeoutException;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResiliencePolicyExecutorTest {

    private final ResiliencePolicyExecutor exec = new ResiliencePolicyExecutor();

    private ResiliencePolicy policy(int retries) {
        return ResiliencePolicy.builder()
                .connectTimeoutMs(200).readTimeoutMs(300).retries(retries)
                .retryOn(Set.of(RetryTrigger.TIMEOUT)).circuitBreaker(null).build();
    }

    @Test
    void retriesOnTimeoutUpToLimit() {
        AtomicInteger calls = new AtomicInteger();
        assertThatThrownBy(() -> exec.execute(policy(1), () -> {
            calls.incrementAndGet();
            throw new HttpTimeoutException("t");
        })).isInstanceOf(HttpTimeoutException.class);
        assertThat(calls.get()).isEqualTo(2); // 首次 + 1 重试
    }

    @Test
    void doesNotRetryNonMatchingError() {
        AtomicInteger calls = new AtomicInteger();
        assertThatThrownBy(() -> exec.execute(policy(2), () -> {
            calls.incrementAndGet();
            throw new RuntimeException("non-timeout");
        })).isInstanceOf(RuntimeException.class);
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void returnsValueOnSuccess() throws Exception {
        assertThat(exec.execute(policy(1), () -> 42)).isEqualTo(42);
    }
}
