package com.sstlfsj.rule.sdk.source;

import com.sstlfsj.rule.kernel.internal.index.SceneRuleIndex;
import com.sstlfsj.rule.sdk.FetchMode;
import com.sstlfsj.rule.sdk.SnapshotPoller;

import java.time.Duration;
import java.util.List;

/**
 * HTTP 轮询模式：封装 SnapshotPoller，定期从服务端拉取最新快照写入索引。
 * loadInto() 启动后台轮询线程；调用方负责在 close() 时调用 stop()。
 */
public class PollingRuleSource implements RuleSource {

    private final String serverUrl;
    private final String tenantId;
    private final FetchMode fetchMode;
    private final List<String> scenes;
    private final Duration pollInterval;
    private SnapshotPoller poller;

    public PollingRuleSource(String serverUrl, String tenantId,
                             FetchMode fetchMode, List<String> scenes,
                             Duration pollInterval) {
        this.serverUrl = serverUrl;
        this.tenantId = tenantId;
        this.fetchMode = fetchMode;
        this.scenes = List.copyOf(scenes);
        this.pollInterval = pollInterval;
    }

    /** 启动后台轮询，index 为本次 loadInto 传入的目标索引。 */
    @Override
    public void loadInto(SceneRuleIndex index) {
        this.poller = new SnapshotPoller(serverUrl, tenantId, fetchMode,
                scenes, pollInterval, index);
        poller.start();
    }

    /** 停止后台轮询线程，供 RuleEngineClient.close() 调用。 */
    public void stop() {
        if (poller != null) poller.stop();
    }
}
