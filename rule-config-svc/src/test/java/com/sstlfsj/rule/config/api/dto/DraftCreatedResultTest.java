package com.sstlfsj.rule.config.api.dto;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class DraftCreatedResultTest {

    @Test
    void fields_roundTrip() {
        DraftCreatedResult r = new DraftCreatedResult(1L, 2L, 1L, "DRAFT");
        assertThat(r.ruleDefinitionId()).isEqualTo(1L);
        assertThat(r.ruleVersionId()).isEqualTo(2L);
        assertThat(r.version()).isEqualTo(1L);
        assertThat(r.status()).isEqualTo("DRAFT");
    }
}
