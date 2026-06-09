package com.sstlfsj.rule.config.internal.domain;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证 SceneActionBindingDef 字段读写映射。 */
class SceneActionBindingDefTest {

    @Test
    void settersAndGetters_roundtrip() {
        LocalDateTime now = LocalDateTime.now();
        SceneActionBindingDef def = new SceneActionBindingDef();
        def.setId(7L);
        def.setSceneId(42L);
        def.setActionType("BLOCK_TX");
        def.setDefaultParams(Map.of("reason", "risk"));
        def.setCreatedBy("alice");
        def.setCreatedAt(now);
        def.setUpdatedBy("bob");
        def.setUpdatedAt(now);

        assertThat(def.getId()).isEqualTo(7L);
        assertThat(def.getSceneId()).isEqualTo(42L);
        assertThat(def.getActionType()).isEqualTo("BLOCK_TX");
        assertThat(def.getDefaultParams()).isEqualTo(Map.of("reason", "risk"));
        assertThat(def.getCreatedBy()).isEqualTo("alice");
        assertThat(def.getCreatedAt()).isEqualTo(now);
        assertThat(def.getUpdatedBy()).isEqualTo("bob");
        assertThat(def.getUpdatedAt()).isEqualTo(now);
    }
}
