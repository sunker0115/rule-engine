package com.sstlfsj.rule.kernel.api.spi.watcher;

import java.util.function.Consumer;

/** 场景激活状态变更时通知订阅方的 SPI 接口。 */
public interface SceneWatcher {
    /** 场景激活状态变更事件。 */
    record SceneChangeEvent(String tenantId, String sceneCode, boolean active) {}

    /**
     * 订阅场景激活状态变更，变更时通过回调通知。
     *
     * @param onUpdate 场景变更事件的消费回调
     */
    void watch(Consumer<SceneChangeEvent> onUpdate);

    /** 停止订阅并释放相关资源。 */
    void stop();
}
