package com.sstlfsj.rule.kernel.api.spi.watcher;

import java.util.function.Consumer;

/** 场景激活状态变更时通知订阅方的 SPI 接口。 */
public interface SceneWatcher {
    record SceneChangeEvent(String tenantId, String sceneCode, boolean active) {}
    void watch(Consumer<SceneChangeEvent> onUpdate);
    void stop();
}
