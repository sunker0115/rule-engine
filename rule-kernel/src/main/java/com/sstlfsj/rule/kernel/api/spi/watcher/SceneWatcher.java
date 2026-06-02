package com.sstlfsj.rule.kernel.api.spi.watcher;

import java.util.function.Consumer;

/** Notifies subscribers when a scene's active status changes. */
public interface SceneWatcher {
    record SceneChangeEvent(String tenantId, String sceneCode, boolean active) {}
    void watch(Consumer<SceneChangeEvent> onUpdate);
    void stop();
}
