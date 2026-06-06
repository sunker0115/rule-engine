package com.sstlfsj.rule.sdk.source;

import com.sstlfsj.rule.sdk.FetchMode;
import com.sstlfsj.rule.sdk.MetricDefinitionPoller;
import com.sstlfsj.rule.sdk.metric.MetricDefinitionRegistry;

import java.time.Duration;
import java.util.List;

/**
 * HTTP 轮询模式定义来源：封装 {@link MetricDefinitionPoller}，定期从服务端拉定义写入注册表。
 * loadInto() 启动后台轮询线程；调用方负责在 close() 时调用 stop()。
 */
public class PollingMetricDefinitionSource implements MetricDefinitionSource {

    private final String serverUrl;
    private final String tenantId;
    private final FetchMode fetchMode;
    private final List<String> scenes;
    private final Duration pollInterval;
    private MetricDefinitionPoller poller;

    /**
     * @param serverUrl    rule-api 服务地址
     * @param tenantId     租户 id
     * @param fetchMode    订阅模式
     * @param scenes       订阅场景列表
     * @param pollInterval 轮询间隔
     */
    public PollingMetricDefinitionSource(String serverUrl, String tenantId, FetchMode fetchMode,
                                         List<String> scenes, Duration pollInterval) {
        this.serverUrl = serverUrl;
        this.tenantId = tenantId;
        this.fetchMode = fetchMode;
        this.scenes = List.copyOf(scenes);
        this.pollInterval = pollInterval;
    }

    @Override
    public void loadInto(MetricDefinitionRegistry registry) {
        this.poller = new MetricDefinitionPoller(serverUrl, tenantId, fetchMode,
                scenes, pollInterval, registry);
        poller.start();
    }

    /** 停止后台轮询线程，供 RuleEngineClient.close() 调用。 */
    public void stop() {
        if (poller != null) poller.stop();
    }
}
