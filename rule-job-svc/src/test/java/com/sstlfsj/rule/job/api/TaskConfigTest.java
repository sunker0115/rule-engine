package com.sstlfsj.rule.job.api;

import com.sstlfsj.rule.eval.api.service.SqlOutcomeSourceConfig;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class TaskConfigTest {

    private final ObjectMapper mapper = JsonMapper.builder().build();

    @Test
    void triggerConfig_polymorphicRoundTrip() {
        TaskConfig cfg = new TriggerConfig("risk.transfer", "login", new BeanMethodQuery("b#m"));
        String json = mapper.writeValueAsString(cfg);
        assertThat(json).contains("\"kind\":\"TRIGGER\"");

        TaskConfig back = mapper.readValue(json, TaskConfig.class);
        assertThat(back).isInstanceOf(TriggerConfig.class);
        assertThat(back.type()).isEqualTo(TaskType.TRIGGER);
        assertThat(((TriggerConfig) back).sceneCode()).isEqualTo("risk.transfer");
        assertThat(((TriggerConfig) back).subjectQuery()).isInstanceOf(BeanMethodQuery.class);
    }

    @Test
    void outcomeIngestionConfig_polymorphicRoundTrip() {
        Instant watermark = Instant.parse("2026-06-19T00:00:00Z");
        TaskConfig cfg = new OutcomeIngestionConfig(
                new SqlOutcomeSourceConfig("ds", "select event_id from t"), watermark);
        String json = mapper.writeValueAsString(cfg);
        assertThat(json).contains("\"kind\":\"OUTCOME_INGESTION\"");

        TaskConfig back = mapper.readValue(json, TaskConfig.class);
        assertThat(back).isInstanceOf(OutcomeIngestionConfig.class);
        assertThat(back.type()).isEqualTo(TaskType.OUTCOME_INGESTION);
        OutcomeIngestionConfig ic = (OutcomeIngestionConfig) back;
        // 验证嵌套 sealed 多态:TaskConfig→OutcomeIngestionConfig→OutcomeSourceConfig
        assertThat(ic.source()).isInstanceOf(SqlOutcomeSourceConfig.class);
        assertThat(((SqlOutcomeSourceConfig) ic.source()).datasource()).isEqualTo("ds");
        assertThat(ic.watermark()).isEqualTo(watermark);
    }
}
