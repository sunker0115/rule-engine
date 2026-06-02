package com.sstlfsj.rule.kernel.api.spi.watcher;

import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;
import java.util.function.Consumer;

/** Notifies subscribers when a rule version is published or updated. */
public interface RuleVersionWatcher {
    void watch(Consumer<RuleVersionSnapshot> onUpdate);
    void stop();
}
