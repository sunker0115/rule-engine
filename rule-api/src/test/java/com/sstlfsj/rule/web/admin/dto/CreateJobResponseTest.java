package com.sstlfsj.rule.web.admin.dto;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CreateJobResponseTest {

    @Test
    void exposesId() {
        assertThat(new CreateJobResponse(42L).id()).isEqualTo(42L);
    }
}
