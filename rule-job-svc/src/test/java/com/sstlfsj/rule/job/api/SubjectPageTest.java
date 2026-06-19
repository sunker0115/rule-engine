package com.sstlfsj.rule.job.api;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SubjectPageTest {

    @Test
    void offsetIsPageNumberTimesPageSize() {
        assertThat(new SubjectPage(0, 500).offset()).isEqualTo(0L);
        assertThat(new SubjectPage(3, 500).offset()).isEqualTo(1500L);
    }

    @Test
    void offsetDoesNotOverflowInt() {
        // pageNumber * pageSize 用 long 计算，避免大页码下 int 溢出
        assertThat(new SubjectPage(5_000_000, 500).offset()).isEqualTo(2_500_000_000L);
    }
}
