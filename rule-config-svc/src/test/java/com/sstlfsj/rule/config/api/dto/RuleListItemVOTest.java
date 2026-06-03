package com.sstlfsj.rule.config.api.dto;

import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import static org.assertj.core.api.Assertions.assertThat;

class RuleListItemVOTest {

    @Test
    void fields_roundTrip() {
        LocalDateTime publishedAt = LocalDateTime.of(2026, 6, 1, 10, 0);
        RuleListItemVO vo = new RuleListItemVO(10L, "rule.a", "规则A", "PUBLISHED", 42L, publishedAt);

        assertThat(vo.ruleDefinitionId()).isEqualTo(10L);
        assertThat(vo.code()).isEqualTo("rule.a");
        assertThat(vo.name()).isEqualTo("规则A");
        assertThat(vo.status()).isEqualTo("PUBLISHED");
        assertThat(vo.currentVersion()).isEqualTo(42L);
        assertThat(vo.publishedAt()).isEqualTo(publishedAt);
    }

    @Test
    void nullableFields_allowNull() {
        RuleListItemVO vo = new RuleListItemVO(1L, "rule.b", "规则B", "DRAFT", null, null);

        assertThat(vo.currentVersion()).isNull();
        assertThat(vo.publishedAt()).isNull();
    }
}
