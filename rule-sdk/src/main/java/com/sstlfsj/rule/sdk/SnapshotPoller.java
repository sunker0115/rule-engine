package com.sstlfsj.rule.sdk;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;
import com.sstlfsj.rule.kernel.internal.codec.AstJsonCodec;
import com.sstlfsj.rule.kernel.internal.index.SceneRuleIndex;

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
 * 规则快照同步器：启动时全量拉取，后台线程定时增量刷新。
 * 使用 JDK 内置 HttpClient，无额外依赖；使用 AstJsonCodec 保证 AstNode 多态反序列化正确。
 */
public class SnapshotPoller {

    private final String serverUrl;
    private final String tenantId;
    private final FetchMode fetchMode;
    private final List<String> scenes;
    private final Duration pollInterval;
    private final SceneRuleIndex index;
    private final ObjectMapper mapper;
    private final HttpClient httpClient;
    private ScheduledExecutorService scheduler;

    public SnapshotPoller(String serverUrl, String tenantId, FetchMode fetchMode,
                          List<String> scenes, Duration pollInterval, SceneRuleIndex index) {
        this.serverUrl = serverUrl;
        this.tenantId = tenantId;
        this.fetchMode = fetchMode;
        this.scenes = List.copyOf(scenes);
        this.pollInterval = pollInterval;
        this.index = index;
        // 使用 AstJsonCodec 配置好的 ObjectMapper，支持 AstNode 多态类型反序列化
        this.mapper = new AstJsonCodec().createMapper();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    /** 启动全量拉取并开启后台轮询线程。 */
    public void start() {
        poll();
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "rule-sdk-poller");
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
                    .uri(URI.create(buildUrl()))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                JsonNode root = mapper.readTree(resp.body());
                List<RuleVersionSnapshot> snapshots = mapper.convertValue(
                        root.get("data"),
                        new TypeReference<List<RuleVersionSnapshot>>() {});
                refreshIndex(snapshots);
            }
        } catch (Exception e) {
            // 轮询失败静默处理，保持最后一次成功的索引状态
            System.err.println("[SnapshotPoller] 轮询失败: " + e.getMessage());
        }
    }

    private void refreshIndex(List<RuleVersionSnapshot> snapshots) {
        for (RuleVersionSnapshot snap : snapshots) {
            List<String> keys = snap.triggerEventTypes().isEmpty()
                    ? List.of("*") : snap.triggerEventTypes();
            for (String key : keys) {
                index.update(snap.tenantId(), snap.sceneCode(), key, List.of(snap));
            }
        }
    }

    private String buildUrl() {
        StringBuilder url = new StringBuilder(serverUrl)
                .append("/api/v1/sdk/snapshots?tenantId=")
                .append(tenantId);
        if (fetchMode == FetchMode.DECLARED && !scenes.isEmpty()) {
            url.append("&scenes=").append(String.join(",", scenes));
        }
        return url.toString();
    }
}
