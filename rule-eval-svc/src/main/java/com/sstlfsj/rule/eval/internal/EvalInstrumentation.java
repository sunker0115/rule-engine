package com.sstlfsj.rule.eval.internal;

import com.sstlfsj.rule.eval.internal.dispatch.PushEventDispatcher;
import com.sstlfsj.rule.observability.api.metrics.RuleMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * eval-svc 侧可观测性埋点，封装 Counter + Gauge 注册逻辑，使 EvalServiceImpl 专注评估协调。
 */
public final class EvalInstrumentation {

    private final Counter evalTotal;
    private final Counter evalError;
    private final MeterRegistry meterRegistry;

    /**
     * @param evalTotal    评估总次数 Counter（RuleMetrics.EVAL_TOTAL）
     * @param evalError    评估错误 Counter（RuleMetrics.EVAL_ERROR_TOTAL）
     * @param meterRegistry Micrometer 注册表（用于注册 trace 队列 Gauge）
     */
    public EvalInstrumentation(Counter evalTotal, Counter evalError,
                               MeterRegistry meterRegistry) {
        this.evalTotal = evalTotal;
        this.evalError = evalError;
        this.meterRegistry = meterRegistry;
    }

    /**
     * 每次评估完成时调用：无条件计 evalTotal，有 errorCode 时计 evalError。
     *
     * @param isError 本次评估结果的 errorCode 是否非 null
     */
    public void record(boolean isError) {
        evalTotal.increment();
        if (isError) evalError.increment();
    }

    /**
     * 在 PushEventDispatcher 启动后调用，注册 trace 队列利用率 Gauge（0~1）。
     * 必须在 dispatcher.start() 之后调用（queue 此时已初始化）。
     *
     * @param dispatcher 已启动的派发器
     */
    public void registerQueueGauge(PushEventDispatcher dispatcher) {
        Gauge.builder(RuleMetrics.TRACE_QUEUE_SIZE, dispatcher,
                d -> d.queueCapacity() > 0
                        ? (double) d.queueSize() / d.queueCapacity()
                        : 0.0)
                .description("trace 队列利用率（queueSize/capacity，0~1）")
                .register(meterRegistry);
    }
}
