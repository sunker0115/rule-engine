package com.sstlfsj.rule.eval.internal.service;

import com.sstlfsj.rule.eval.api.FetchTrace;
import com.sstlfsj.rule.eval.api.service.MetricFetchTestService;
import com.sstlfsj.rule.eval.internal.metric.fetch.MetricFetchTester;
import org.springframework.stereotype.Service;

import java.util.Map;

/** MetricFetchTestService 实现：薄委托 {@link MetricFetchTester}，自身不含取数逻辑。 */
@Service
class MetricFetchTestServiceImpl implements MetricFetchTestService {

    private final MetricFetchTester tester;

    MetricFetchTestServiceImpl(MetricFetchTester tester) {
        this.tester = tester;
    }

    @Override
    public FetchTrace test(Long tenantId, String metricCode,
                           Map<String, Object> sampleVars, Map<String, Object> samplePayload, String sampleSubjectId) {
        return tester.test(tenantId, metricCode, sampleVars, samplePayload, sampleSubjectId);
    }

    @Override
    public FetchTrace testConnector(Long tenantId, String connectorCode,
                                    Map<String, Object> sampleVars, Map<String, Object> samplePayload, String sampleSubjectId) {
        return tester.testConnector(tenantId, connectorCode, sampleVars, samplePayload, sampleSubjectId);
    }
}
