package com.sstlfsj.rule.eval.internal.metric.sql;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SqlAggregateMetricSourceHandlerTest {

    @Test
    void bind_replacesDottedPlaceholders_andBindsValues() {
        Instant now = Instant.parse("2026-06-06T00:00:00Z");
        SqlAggregateMetricSourceHandler.Bound b = SqlAggregateMetricSourceHandler.bind(
                "SELECT COUNT(*) FROM t WHERE uid = :subjectId AND amt > :payload.amt "
                        + "AND created_at >= :now - INTERVAL :params.win DAY",
                "u1", "1", now, Map.of("amt", 500), Map.of("win", 7));

        assertThat(b.sql()).contains(":subjectId").contains(":payload_amt")
                .contains(":params_win").contains(":now").doesNotContain(":payload.amt");
        MapSqlParameterSource src = b.params();
        assertThat(src.getValue("subjectId")).isEqualTo("u1");
        assertThat(src.getValue("payload_amt")).isEqualTo(500);
        assertThat(src.getValue("params_win")).isEqualTo(7);
        assertThat(src.getValue("now")).isNotNull();
    }
}
