package com.sstlfsj.rule.config.api.service;

import com.sstlfsj.rule.config.api.dto.PayloadFieldSpec;
import com.sstlfsj.rule.config.api.dto.SceneDetailDto;
import com.sstlfsj.rule.config.api.dto.SceneListItem;
import com.sstlfsj.rule.config.api.dto.UpdateSceneCommand;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;

/** 验证 SceneService 契约：stub 实现编译通过、方法签名匹配。 */
class SceneServiceTest {

    private final SceneService stub = new SceneService() {
        @Override
        public Long createScene(String tenantId, String sceneCode, String name,
                                String description, String dominantMode, String subjectType,
                                List<String> eventTypes, List<PayloadFieldSpec> payloadSchema,
                                Map<String, Object> defaultParams, String actorId) {
            throw new UnsupportedOperationException("stub");
        }

        @Override
        public void updateScene(UpdateSceneCommand cmd) {
            throw new UnsupportedOperationException("stub");
        }

        @Override
        public SceneDetailDto getScene(String tenantId, String sceneCode) {
            throw new UnsupportedOperationException("stub");
        }

        @Override
        public void disableScene(String tenantId, String sceneCode, String actorId) {
            throw new UnsupportedOperationException("stub");
        }

        @Override
        public List<SceneListItem> listScenes(String tenantId, String status) {
            throw new UnsupportedOperationException("stub");
        }

        @Override
        public void toggleSceneStatus(String tenantId, String sceneCode, boolean enable, String actorId) {
            throw new UnsupportedOperationException("stub");
        }

        @Override
        public SensitiveRefs getSensitiveRefs(String tenantId, String sceneCode) {
            throw new UnsupportedOperationException("stub");
        }
    };

    @Test
    void createScene_stubThrowsUnsupported() {
        assertThrows(UnsupportedOperationException.class,
                () -> stub.createScene("t1", "SCENE_A", "场景A",
                        null, null, null, null, null, null, "actor"));
    }

    @Test
    void updateScene_stubThrowsUnsupported() {
        assertThrows(UnsupportedOperationException.class,
                () -> stub.updateScene(new UpdateSceneCommand("t1", "SCENE_A", null, null, null, null, null, "actor")));
    }

    @Test
    void getScene_stubThrowsUnsupported() {
        assertThrows(UnsupportedOperationException.class,
                () -> stub.getScene("t1", "SCENE_A"));
    }

    @Test
    void disableScene_stubThrowsUnsupported() {
        assertThrows(UnsupportedOperationException.class,
                () -> stub.disableScene("t1", "SCENE_A", "actor"));
    }

    @Test
    void listScenes_stubThrowsUnsupported() {
        assertThrows(UnsupportedOperationException.class,
                () -> stub.listScenes("t1", null));
    }
}
