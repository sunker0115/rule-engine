package com.sstlfsj.rule.eval.internal.metric.fetch;

import com.sstlfsj.rule.config.api.connector.ConnectorDescriptor;
import com.sstlfsj.rule.eval.api.FetchTrace;
import com.sstlfsj.rule.eval.internal.metric.http.ConnectorDefinitionResolver;
import com.sstlfsj.rule.kernel.api.annotation.MetricSourceType;
import com.sstlfsj.rule.kernel.api.model.MetricDescriptor;
import com.sstlfsj.rule.kernel.api.model.MetricFetchError;
import com.sstlfsj.rule.kernel.api.model.MetricQuery;
import com.sstlfsj.rule.kernel.api.model.SourceType;
import com.sstlfsj.rule.kernel.api.spi.metric.FetchTraceCollector;
import com.sstlfsj.rule.kernel.api.spi.metric.MetricDefinitionResolver;
import com.sstlfsj.rule.kernel.api.spi.metric.MetricSourceHandler;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 跨源取数自助测试器：给样例输入实打实取数一次，捕获分阶段 {@link FetchTrace}（供 :test 端点）。
 * <p>不复制取数逻辑——按 sourceType 路由到 P2 的 handler，调其带 {@link FetchTraceCollector} 的取数重载，
 * 单一编排同时产出取数结果与 trace。handler 路由与 {@code EvalContextAssembler} 同款（按 {@link MetricSourceType} 归类）。</p>
 */
@Component
public class MetricFetchTester {

    private final Map<String, MetricSourceHandler> handlersBySourceType;
    private final MetricDefinitionResolver definitionResolver;
    private final ConnectorDefinitionResolver connectorResolver;

    /**
     * @param metricHandlers     取数 handler 列表（按 {@link MetricSourceType} 归类为 sourceType→handler）
     * @param definitionResolver metric 定义解析器
     * @param connectorResolver  连接器描述符解析器（connector 直测用）
     */
    public MetricFetchTester(List<MetricSourceHandler> metricHandlers,
                             MetricDefinitionResolver definitionResolver,
                             ConnectorDefinitionResolver connectorResolver) {
        Map<String, MetricSourceHandler> bySource = new HashMap<>();
        for (MetricSourceHandler h : metricHandlers) {
            MetricSourceType ann = h.getClass().getAnnotation(MetricSourceType.class);
            if (ann != null) bySource.put(ann.value(), h);
        }
        this.handlersBySourceType = Map.copyOf(bySource);
        this.definitionResolver = definitionResolver;
        this.connectorResolver = connectorResolver;
    }

    /**
     * 用样例输入对某 metric 实打实取数一次，返回分阶段 trace。
     * 解析 metric 定义 → 注入样例 vars 到 params.vars → 调对应 sourceType 的 handler traced 取数。
     *
     * @param tenantId        租户 id
     * @param metricCode      被测 metric
     * @param sampleVars      样例 vars（异构样本，Map 合规例外）
     * @param samplePayload   样例 payload（异构样本）
     * @param sampleSubjectId 样例主体 id
     * @return 分阶段 trace（定义缺失 / handler 缺失时 errorCode 为 NOT_FOUND，sourceType 尽可能填）
     */
    public FetchTrace test(Long tenantId, String metricCode,
                           Map<String, Object> sampleVars, Map<String, Object> samplePayload,
                           String sampleSubjectId) {
        MetricDescriptor def = definitionResolver.resolve(String.valueOf(tenantId), metricCode, 1);
        if (def == null) {
            return new FetchTrace(null, null, null, null, null, null, MetricFetchError.NOT_FOUND.tag());
        }
        Map<String, Object> params = new HashMap<>(def.params());
        params.put("vars", sampleVars == null ? Map.of() : sampleVars);
        MetricQuery query = new MetricQuery(metricCode, String.valueOf(tenantId), sampleSubjectId,
                params, samplePayload == null ? Map.of() : samplePayload, Instant.now());
        return run(def.sourceType(), query);
    }

    /**
     * 直测某连接器（不经 metric）：构造临时 MetricQuery 直走 EXTERNAL_HTTP handler traced 取数。
     * 解析连接器描述符以读其 dataType 无关——valuePath 取到的原始值不强转（dataType 留空）。
     *
     * @param tenantId        租户 id
     * @param connectorCode   被测连接器
     * @param sampleVars      样例 vars（异构样本，Map 合规例外）
     * @param samplePayload   样例 payload（异构样本）
     * @param sampleSubjectId 样例主体 id
     * @return 分阶段 trace（连接器缺失时 errorCode 为 NOT_FOUND）
     */
    public FetchTrace testConnector(Long tenantId, String connectorCode,
                                    Map<String, Object> sampleVars, Map<String, Object> samplePayload,
                                    String sampleSubjectId) {
        ConnectorDescriptor descriptor = connectorResolver.resolve(tenantId, connectorCode);
        if (descriptor == null) {
            return new FetchTrace(SourceType.EXTERNAL_HTTP, null, null, null, null, null,
                    MetricFetchError.NOT_FOUND.tag());
        }
        Map<String, Object> params = new HashMap<>();
        params.put("connector", connectorCode);
        params.put("vars", sampleVars == null ? Map.of() : sampleVars);
        MetricQuery query = new MetricQuery(connectorCode, String.valueOf(tenantId), sampleSubjectId,
                params, samplePayload == null ? Map.of() : samplePayload, Instant.now());
        return run(SourceType.EXTERNAL_HTTP, query);
    }

    /** 路由到 sourceType 对应 handler，传累积 collector 取数，组装 FetchTrace。 */
    private FetchTrace run(String sourceType, MetricQuery query) {
        MetricSourceHandler handler = handlersBySourceType.get(sourceType);
        CapturingCollector collector = new CapturingCollector();
        if (handler == null) {
            return new FetchTrace(sourceType, null, null, null, null, null, MetricFetchError.NOT_FOUND.tag());
        }
        handler.fetch(query, collector);
        return collector.toTrace(sourceType);
    }

    /** 累积各阶段 trace 字段，取数后组装为 {@link FetchTrace}。 */
    private static final class CapturingCollector implements FetchTraceCollector {
        private String renderedRequest;
        private String boundSql;
        private String rawResponse;
        private Boolean successMatched;
        private Object mappedValue;
        private String errorCode;

        @Override public void renderedRequest(String v) { this.renderedRequest = v; }
        @Override public void boundSql(String v) { this.boundSql = v; }
        @Override public void rawResponse(String v) { this.rawResponse = v; }
        @Override public void successMatched(boolean v) { this.successMatched = v; }
        @Override public void mappedValue(Object v) { this.mappedValue = v; }
        @Override public void errorCode(String v) { this.errorCode = v; }

        FetchTrace toTrace(String sourceType) {
            return new FetchTrace(sourceType, renderedRequest, boundSql, rawResponse,
                    successMatched, mappedValue, errorCode);
        }
    }
}
