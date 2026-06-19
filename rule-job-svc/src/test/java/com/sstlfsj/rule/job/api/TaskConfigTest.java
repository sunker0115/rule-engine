package com.sstlfsj.rule.job.api;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

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
}
