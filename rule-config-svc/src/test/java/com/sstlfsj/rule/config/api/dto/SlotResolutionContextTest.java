package com.sstlfsj.rule.config.api.dto;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SlotResolutionContextTest {

    @Test
    void fields_roundTrip() {
        SlotResolutionContext ctx = new SlotResolutionContext(1L, "PAYMENT");
        assertThat(ctx.tenantId()).isEqualTo(1L);
        assertThat(ctx.sceneCode()).isEqualTo("PAYMENT");
    }

    @Test
    void nullSceneCode_isAllowed() {
        SlotResolutionContext ctx = new SlotResolutionContext(1L, null);
        assertThat(ctx.tenantId()).isEqualTo(1L);
        assertThat(ctx.sceneCode()).isNull();
    }
}
