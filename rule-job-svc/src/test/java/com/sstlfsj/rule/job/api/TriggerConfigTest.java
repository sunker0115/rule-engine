package com.sstlfsj.rule.job.api;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/** TriggerConfig 普通 Jackson 往返(去中心化后无 sealed 基类、无 kind 判别)。 */
class TriggerConfigTest {

    private final ObjectMapper mapper = JsonMapper.builder().build();

    @Test
    void plainRoundTrip() {
        TriggerConfig cfg = new TriggerConfig("risk.transfer", "login", new BeanMethodQuery("b#m"));
        String json = mapper.writeValueAsString(cfg);
        // 去中心化:不再有多态 kind 判别字段
        assertThat(json).doesNotContain("\"kind\"");

        TriggerConfig back = mapper.readValue(json, TriggerConfig.class);
        assertThat(back.sceneCode()).isEqualTo("risk.transfer");
        assertThat(back.eventType()).isEqualTo("login");
        assertThat(back.subjectQuery()).isInstanceOf(BeanMethodQuery.class);
        assertThat(((BeanMethodQuery) back.subjectQuery()).ref()).isEqualTo("b#m");
    }
}
