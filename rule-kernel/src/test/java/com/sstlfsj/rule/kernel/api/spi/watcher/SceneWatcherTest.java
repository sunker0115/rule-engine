package com.sstlfsj.rule.kernel.api.spi.watcher;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class SceneWatcherTest {

    @Test
    void sceneChangeEvent_recordFieldsAreAccessible() {
        SceneWatcher.SceneChangeEvent event =
                new SceneWatcher.SceneChangeEvent("t1", "SCENE1", true);
        assertEquals("t1", event.tenantId());
        assertEquals("SCENE1", event.sceneCode());
        assertTrue(event.active());
    }

    @Test
    void watch_callbackIsInvokedWithEvent() {
        List<SceneWatcher.SceneChangeEvent> received = new ArrayList<>();

        SceneWatcher watcher = new SceneWatcher() {
            @Override
            public void watch(Consumer<SceneChangeEvent> onUpdate) {
                onUpdate.accept(new SceneChangeEvent("t1", "SCENE1", false));
            }

            @Override
            public void stop() {}
        };

        watcher.watch(received::add);

        assertEquals(1, received.size());
        assertFalse(received.get(0).active());
    }

    @Test
    void stop_canBeCalledWithoutError() {
        SceneWatcher watcher = new SceneWatcher() {
            @Override
            public void watch(Consumer<SceneChangeEvent> onUpdate) {}

            @Override
            public void stop() {}
        };
        assertDoesNotThrow(watcher::stop);
    }
}
