package com.sstlfsj.rule.job.api;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

class SubjectQueryTest {

    private final ObjectMapper mapper = JsonMapper.builder().build();

    @Test
    void beanMethodQuery_roundTripWithTypeField() {
        SubjectQuery q = new BeanMethodQuery("demoFraudJob#recentLoginUsers");
        String json = mapper.writeValueAsString(q);
        assertThat(json).contains("\"type\":\"BEAN_METHOD\"");
        assertThat(json).contains("\"ref\":\"demoFraudJob#recentLoginUsers\"");

        SubjectQuery back = mapper.readValue(json, SubjectQuery.class);
        assertThat(back).isInstanceOf(BeanMethodQuery.class);
        assertThat(((BeanMethodQuery) back).ref()).isEqualTo("demoFraudJob#recentLoginUsers");
    }

    @Test
    void deserializeFromStoredJson() {
        SubjectQuery back = mapper.readValue(
                "{\"type\":\"BEAN_METHOD\",\"ref\":\"a#b\"}", SubjectQuery.class);
        assertThat(((BeanMethodQuery) back).ref()).isEqualTo("a#b");
    }
}
