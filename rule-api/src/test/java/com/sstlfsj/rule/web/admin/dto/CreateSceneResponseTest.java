package com.sstlfsj.rule.web.admin.dto;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CreateSceneResponseTest {

    @Test
    void 暴露_id() {
        assertThat(new CreateSceneResponse(42L).id()).isEqualTo(42L);
    }
}
