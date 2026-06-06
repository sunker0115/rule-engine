package com.sstlfsj.rule.sdk;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.sstlfsj.rule.kernel.api.model.MetricDescriptor;
import com.sstlfsj.rule.sdk.metric.MetricDefinitionRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * metric 定义同步器：启动时全量拉取，后台线程定时刷新。镜像 {@link SnapshotPoller}，
 * 端点为 {@code /api/v1/sdk/metric-definitions}，结果整体替换该租户的本地定义集合。
 * 用 JDK 内置 HttpClient + 普通 ObjectMapper（MetricDescriptor 无多态字段）。
 */
public class MetricDefinitionPoller {

    private static final Logger log = LoggerFactory.getLogger(MetricDefinitionPoller.class);

    private final String serverUrl;
    private final String tenantId;
    private final FetchMode fetchMode;
    private final List<String> scenes;
    private final Duration pollInterval;
    private final MetricDefinitionRegistry registry;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private ScheduledExecutorService scheduler;

    /**
     * @param serverUrl    rule-api 服务地址
     * @param tenantId     租户 id
     * @param fetchMode    订阅模式（DECLARED 时按 scenes 过滤）
     * @param scenes       订阅场景列表
     * @param pollInterval 轮询间隔
     * @param registry     目标定义注册表
     */
    public MetricDefinitionPoller(String serverUrl, String tenantId, FetchMode fetchMode,
                                  List<String> scenes, Duration pollInterval,
                                  MetricDefinitionRegistry registry) {
        this.serverUrl = serverUrl;
        this.tenantId = tenantId;
        this.fetchMode = fetchMode;
        this.scenes = List.copyOf(scenes);
        this.pollInterval = pollInterval;
        this.registry = registry;
    }

    /** 启动全量拉取并开启后台轮询线程。 */
    public void start() {
        poll();
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "rule-sdk-metric-poller");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(this::poll,
                pollInterval.toSeconds(), pollInterval.toSeconds(), TimeUnit.SECONDS);
    }

    /** 停止后台轮询线程。 */
    public void stop() {
        if (scheduler != null) scheduler.shutdown();
    }

    private void poll() {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(buildUrl(serverUrl, tenantId, fetchMode, scenes)))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                JsonNode root = mapper.readTree(resp.body());
                List<MetricDescriptor> defs = mapper.convertValue(
                        root.get("data"),
                        new TypeReference<List<MetricDescriptor>>() {});
                registry.replaceAll(tenantId, defs);
            }
        } catch (Exception e) {
            // 轮询失败保持最后一次成功的定义集合，记录告警供排障
            log.warn("metric 定义轮询失败: {}", e.getMessage());
        }
    }

    /** 构造下发端点 URL；DECLARED 且 scenes 非空时附加 scenes 参数。 */
    static String buildUrl(String serverUrl, String tenantId, FetchMode fetchMode, List<String> scenes) {
        StringBuilder url = new StringBuilder(serverUrl)
                .append("/api/v1/sdk/metric-definitions?tenantId=")
                .append(tenantId);
        if (fetchMode == FetchMode.DECLARED && !scenes.isEmpty()) {
            url.append("&scenes=").append(String.join(",", scenes));
        }
        return url.toString();
    }
}
