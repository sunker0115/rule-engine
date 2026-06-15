package com.sstlfsj.rule.eval.internal.metric.fetch;

import com.sstlfsj.rule.config.api.connector.ResiliencePolicy;
import com.sstlfsj.rule.config.api.connector.RetryTrigger;

import java.net.http.HttpTimeoutException;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeoutException;

/**
 * 最小弹性执行器（共用脊）：按 RetryTrigger 重试。超时由调用方设（HttpClient/statement），
 * 不引 Resilience4j。熔断（CircuitBreakerPolicy）作后续增强位，v1 仅 retry。
 */
public class ResiliencePolicyExecutor {

    /**
     * 按策略执行取数动作，可重试。
     *
     * @param policy 弹性策略
     * @param action 取数动作（抛异常表失败）
     * @return 动作结果
     * @throws Exception 重试耗尽后的最后一次异常
     */
    public <T> T execute(ResiliencePolicy policy, Callable<T> action) throws Exception {
        int attempts = Math.max(0, policy.retries()) + 1;
        Exception last = null;
        for (int i = 0; i < attempts; i++) {
            try {
                return action.call();
            } catch (Exception e) {
                last = e;
                if (i == attempts - 1 || !retryable(e, policy)) throw e;
            }
        }
        throw last;
    }

    private boolean retryable(Exception e, ResiliencePolicy policy) {
        if (policy.retryOn() == null) return false;
        boolean timeout = e instanceof HttpTimeoutException || e instanceof TimeoutException;
        if (timeout && policy.retryOn().contains(RetryTrigger.TIMEOUT)) return true;
        // 5xx 由 handler 主动抛 RetryableUpstreamStatusException（正常响应不抛异常），retryOn 含 UPSTREAM_5XX 才重试
        return e instanceof RetryableUpstreamStatusException
                && policy.retryOn().contains(RetryTrigger.UPSTREAM_5XX);
    }
}
