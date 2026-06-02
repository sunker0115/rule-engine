package com.sstlfsj.rule.kernel.polling;

import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;
import com.sstlfsj.rule.kernel.api.spi.watcher.RuleVersionWatcher;

import java.util.function.Consumer;

/**
 * SDK 嵌入模式（无共享 Spring 容器）下的 DB 轮询实现。
 * 定期扫描 rule_version 表变更，触发回调更新倒排索引。
 */
public class DbPollingRuleWatcher implements RuleVersionWatcher {

    private final int intervalSeconds;
    private volatile boolean running = false;

    public DbPollingRuleWatcher(int intervalSeconds) {
        this.intervalSeconds = intervalSeconds;
    }

    @Override
    public void watch(Consumer<RuleVersionSnapshot> onUpdate) {
        running = true;
        throw new UnsupportedOperationException("DbPollingRuleWatcher not yet implemented (SDK v2)");
    }

    @Override
    public void stop() {
        running = false;
    }
}
