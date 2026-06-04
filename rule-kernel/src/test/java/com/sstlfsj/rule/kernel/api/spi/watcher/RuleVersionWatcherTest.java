package com.sstlfsj.rule.kernel.api.spi.watcher;

import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class RuleVersionWatcherTest {

    @Test
    void watch_callbackIsInvokedWithSnapshot() {
        List<RuleVersionSnapshot> received = new ArrayList<>();

        RuleVersionWatcher watcher = new RuleVersionWatcher() {
            @Override
            public void watch(Consumer<RuleVersionSnapshot> onUpdate) {
                // 立即推送一个快照，模拟更新通知。
                RuleVersionSnapshot snapshot = new RuleVersionSnapshot(
                        1L, "SCENE1", "t1", null, null, null, null);
                onUpdate.accept(snapshot);
            }

            @Override
            public void stop() {}
        };

        watcher.watch(received::add);

        assertEquals(1, received.size());
        assertEquals(1L, received.get(0).ruleVersionId());
    }

    @Test
    void stop_canBeCalledWithoutError() {
        RuleVersionWatcher watcher = new RuleVersionWatcher() {
            @Override
            public void watch(Consumer<RuleVersionSnapshot> onUpdate) {}

            @Override
            public void stop() {}
        };
        assertDoesNotThrow(watcher::stop);
    }
}
