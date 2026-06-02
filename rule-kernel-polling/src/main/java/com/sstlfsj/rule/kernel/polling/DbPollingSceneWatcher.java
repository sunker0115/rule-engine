package com.sstlfsj.rule.kernel.polling;

import com.sstlfsj.rule.kernel.api.spi.watcher.SceneWatcher;

import java.util.function.Consumer;

/**
 * SDK 嵌入模式下的 Scene 状态 DB 轮询实现。
 */
public class DbPollingSceneWatcher implements SceneWatcher {

    private final int intervalSeconds;
    private volatile boolean running = false;

    public DbPollingSceneWatcher(int intervalSeconds) {
        this.intervalSeconds = intervalSeconds;
    }

    @Override
    public void watch(Consumer<SceneChangeEvent> onUpdate) {
        running = true;
        throw new UnsupportedOperationException("DbPollingSceneWatcher not yet implemented (SDK v2)");
    }

    @Override
    public void stop() {
        running = false;
    }
}
