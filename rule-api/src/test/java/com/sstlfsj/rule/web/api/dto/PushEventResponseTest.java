package com.sstlfsj.rule.web.api.dto;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PushEventResponseTest {

    @Test
    void 暴露_eventId_与_accepted() {
        PushEventResponse resp = new PushEventResponse("evt-1", true);
        assertThat(resp.eventId()).isEqualTo("evt-1");
        assertThat(resp.accepted()).isTrue();
    }
}
