package com.sstlfsj.rule.eval.internal.service;

import com.sstlfsj.rule.eval.api.FetchTrace;
import com.sstlfsj.rule.eval.internal.metric.fetch.MetricFetchTester;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** MetricFetchTestServiceImpl 薄委托测试：验证两方法原样转调 MetricFetchTester。 */
class MetricFetchTestServiceImplTest {

    @Test
    void testDelegatesToTester() {
        MetricFetchTester tester = mock(MetricFetchTester.class);
        MetricFetchTestServiceImpl service = new MetricFetchTestServiceImpl(tester);
        FetchTrace expected = new FetchTrace("EXTERNAL_HTTP", "GET /x", null, "body", true, 42, null);
        Map<String, Object> vars = Map.of("k", "v");
        Map<String, Object> payload = Map.of("p", 1);
        when(tester.test(eq(7L), eq("m1"), any(), any(), eq("sub1"))).thenReturn(expected);

        FetchTrace actual = service.test(7L, "m1", vars, payload, "sub1");

        assertThat(actual).isSameAs(expected);
        verify(tester).test(7L, "m1", vars, payload, "sub1");
    }

    @Test
    void testConnectorDelegatesToTester() {
        MetricFetchTester tester = mock(MetricFetchTester.class);
        MetricFetchTestServiceImpl service = new MetricFetchTestServiceImpl(tester);
        FetchTrace expected = new FetchTrace("EXTERNAL_HTTP", "GET /c", null, "ok", true, "v", null);
        Map<String, Object> vars = Map.of("a", "b");
        Map<String, Object> payload = Map.of();
        when(tester.testConnector(eq(7L), eq("c1"), any(), any(), eq("sub1"))).thenReturn(expected);

        FetchTrace actual = service.testConnector(7L, "c1", vars, payload, "sub1");

        assertThat(actual).isSameAs(expected);
        verify(tester).testConnector(7L, "c1", vars, payload, "sub1");
    }
}
