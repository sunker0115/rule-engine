package com.sstlfsj.rule.sdk.source;

import com.sstlfsj.rule.sdk.FetchMode;
import com.sstlfsj.rule.sdk.metric.MetricDefinitionRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;

class PollingMetricDefinitionSourceTest {

    @Test
    void loadInto_deadPort_silentlyFails_andStopIsSafe() {
        MetricDefinitionRegistry registry = new MetricDefinitionRegistry();
        PollingMetricDefinitionSource source = new PollingMetricDefinitionSource(
                "http://localhost:19999", "t1", FetchMode.ALL, List.of(), Duration.ofHours(1));
        assertThatCode(() -> {
            source.loadInto(registry);   // 连不存在端口，poll 静默失败，registry 保持空
            source.stop();
        }).doesNotThrowAnyException();
    }
}
