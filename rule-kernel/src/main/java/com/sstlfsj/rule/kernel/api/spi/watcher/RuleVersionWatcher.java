package com.sstlfsj.rule.kernel.api.spi.watcher;

import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;
import java.util.function.Consumer;

/** 规则版本发布或更新时通知订阅方的 SPI 接口。 */
public interface RuleVersionWatcher {
    void watch(Consumer<RuleVersionSnapshot> onUpdate);
    void stop();
}
