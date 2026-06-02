package com.sstlfsj.rule.kernel.polling;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DbPollingRuleWatcherTest {

    @Test
    void constructor_acceptsPositiveInterval() {
        assertDoesNotThrow(() -> new DbPollingRuleWatcher(30));
    }

    @Test
    void watch_throwsUnsupportedOperationException() {
        var watcher = new DbPollingRuleWatcher(30);
        assertThrows(UnsupportedOperationException.class, () -> watcher.watch(snapshot -> {}));
    }

    @Test
    void stop_doesNotThrow() {
        var watcher = new DbPollingRuleWatcher(30);
        assertDoesNotThrow(watcher::stop);
    }

    @Test
    void stop_afterWatch_doesNotThrow() {
        var watcher = new DbPollingRuleWatcher(30);
        try {
            watcher.watch(snapshot -> {});
        } catch (UnsupportedOperationException ignored) {
            // expected
        }
        assertDoesNotThrow(watcher::stop);
    }
}
