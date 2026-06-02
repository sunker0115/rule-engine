package com.sstlfsj.rule.kernel.polling;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DbPollingSceneWatcherTest {

    @Test
    void constructor_acceptsPositiveInterval() {
        assertDoesNotThrow(() -> new DbPollingSceneWatcher(60));
    }

    @Test
    void watch_throwsUnsupportedOperationException() {
        var watcher = new DbPollingSceneWatcher(60);
        assertThrows(UnsupportedOperationException.class, () -> watcher.watch(event -> {}));
    }

    @Test
    void stop_doesNotThrow() {
        var watcher = new DbPollingSceneWatcher(60);
        assertDoesNotThrow(watcher::stop);
    }

    @Test
    void stop_afterWatch_doesNotThrow() {
        var watcher = new DbPollingSceneWatcher(60);
        try {
            watcher.watch(event -> {});
        } catch (UnsupportedOperationException ignored) {
            // expected
        }
        assertDoesNotThrow(watcher::stop);
    }
}
