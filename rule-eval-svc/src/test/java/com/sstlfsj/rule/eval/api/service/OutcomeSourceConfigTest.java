package com.sstlfsj.rule.eval.api.service;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class OutcomeSourceConfigTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void polymorphicRoundTripKeepsKindAndSubtype() {
        OutcomeSourceConfig cfg = new SqlOutcomeSourceConfig("ds", "select ...");

        String json = mapper.writeValueAsString(cfg);
        assertThat(json).contains("\"kind\":\"SQL\"");

        OutcomeSourceConfig back = mapper.readValue(json, OutcomeSourceConfig.class);
        assertThat(back).isInstanceOf(SqlOutcomeSourceConfig.class);
        SqlOutcomeSourceConfig sql = (SqlOutcomeSourceConfig) back;
        assertThat(sql.datasource()).isEqualTo("ds");
        assertThat(sql.sql()).isEqualTo("select ...");
    }
}
