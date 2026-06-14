package com.sstlfsj.rule.config.api.dto;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SceneListItemTest {

    @Test
    void 暴露各字段() {
        SceneListItem item = new SceneListItem(5L, "1", "PAYMENT", "支付场景", "PUSH", "USER", "ACTIVE", null, null);
        assertThat(item.id()).isEqualTo(5L);
        assertThat(item.sceneCode()).isEqualTo("PAYMENT");
        assertThat(item.name()).isEqualTo("支付场景");
        assertThat(item.dominantMode()).isEqualTo("PUSH");
        assertThat(item.subjectType()).isEqualTo("USER");
        assertThat(item.status()).isEqualTo("ACTIVE");
    }
}
