package com.sstlfsj.rule.kernel.api.spi.watcher;

import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;
import java.util.function.Consumer;

/** 规则版本发布或更新时通知订阅方的 SPI 接口。 */
public interface RuleVersionWatcher {
    /**
     * 订阅规则版本发布/更新，变更时通过回调通知。
     *
     * @param onUpdate 规则版本快照的消费回调
     */
    void watch(Consumer<RuleVersionSnapshot> onUpdate);

    /** 停止订阅并释放相关资源。 */
    void stop();
}
