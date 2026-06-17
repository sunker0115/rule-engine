package com.sstlfsj.rule.config.api.dto;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ConnectorListQueryTest {

    @Test
    void defaultsPageTo1_whenInvalid() {
        var q = new ConnectorListQuery(null, null, null, 0, 20);
        assertThat(q.page()).isEqualTo(1);
    }

    @Test
    void defaultsSizeTo20_whenInvalid() {
        var q = new ConnectorListQuery(null, null, null, 1, 0);
        assertThat(q.size()).isEqualTo(20);
    }

    @Test
    void validQuery_preservesAllFields() {
        var q = new ConnectorListQuery(1L, "risk", "ACTIVE", 2, 10);
        assertThat(q.tenantId()).isEqualTo(1L);
        assertThat(q.keyword()).isEqualTo("risk");
        assertThat(q.status()).isEqualTo("ACTIVE");
        assertThat(q.page()).isEqualTo(2);
        assertThat(q.size()).isEqualTo(10);
    }
}
