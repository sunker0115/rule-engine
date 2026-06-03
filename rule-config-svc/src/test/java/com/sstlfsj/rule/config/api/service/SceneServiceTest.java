package com.sstlfsj.rule.config.api.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

/** 验证 SceneService 契约：方法签名可编译，Stub 实现能正确抛出 UnsupportedOperationException。 */
class SceneServiceTest {

    private final SceneService stub = new SceneService() {
        @Override
        public Long createScene(String tenantId, String sceneCode, String name, String actorId) {
            throw new UnsupportedOperationException("stub");
        }

        @Override
        public void updateScene(String tenantId, String sceneCode, String actorId) {
            throw new UnsupportedOperationException("stub");
        }

        @Override
        public void disableScene(String tenantId, String sceneCode, String actorId) {
            throw new UnsupportedOperationException("stub");
        }
    };

    @Test
    void createScene_stubThrowsUnsupported() {
        assertThrows(UnsupportedOperationException.class,
                () -> stub.createScene("t1", "SCENE_A", "场景A", "actor"));
    }

    @Test
    void updateScene_stubThrowsUnsupported() {
        assertThrows(UnsupportedOperationException.class,
                () -> stub.updateScene("t1", "SCENE_A", "actor"));
    }

    @Test
    void disableScene_stubThrowsUnsupported() {
        assertThrows(UnsupportedOperationException.class,
                () -> stub.disableScene("t1", "SCENE_A", "actor"));
    }
}
